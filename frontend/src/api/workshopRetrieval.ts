/**
 * 制作工坊：检索判定（LLM）+ RAG（rag-service）+ 外部检索（web-search-service，失败时回退 LLM 占位）
 */
import request from './request'
import { getContentFromResponse } from './llm'
import { queryKnowledgeBase } from './rag'
import { formatWebSearchForWorkshop, queryWebSearch } from './webSearch'

interface LlmResult {
  code: number
  message: string
  data: { content?: string }
}

export interface RetrieveJudgeResult {
  need_search: boolean
  search_types: string[]
  keywords: string[]
  reason: string
}

function parseJudgeJson(raw: string | null): RetrieveJudgeResult | null {
  if (!raw || !raw.trim()) return null
  let s = raw.trim().replace(/^```(?:json)?\s*|\s*```$/g, '')
  const start = s.indexOf('{')
  const end = s.lastIndexOf('}')
  if (start >= 0 && end > start) s = s.slice(start, end + 1)
  try {
    const o = JSON.parse(s) as Record<string, unknown>
    const need = Boolean(o.need_search)
    const types = Array.isArray(o.search_types)
      ? (o.search_types as unknown[]).filter((x): x is string => typeof x === 'string')
      : []
    const kw = Array.isArray(o.keywords)
      ? (o.keywords as unknown[]).filter((x): x is string => typeof x === 'string' && x.trim().length > 0).map((k) => k.trim())
      : []
    const reason = typeof o.reason === 'string' ? o.reason : ''
    return { need_search: need, search_types: types, keywords: kw, reason }
  } catch {
    return null
  }
}

async function chatJudge(promptKey: string, userContent: string, timeout = 60000): Promise<string | null> {
  const res = await request.post<LlmResult>(
    '/llm/chat',
    { messages: [{ role: 'user', content: userContent }] },
    { params: { provider: 'qwen', promptKey }, timeout }
  )
  const data = res.data
  if (data?.code !== 200) return null
  return getContentFromResponse(data)
}

/** 形成大纲前：是否检索 + 3 个关键词 */
export async function judgeOutlineRetrieve(params: {
  topic: string
  materialSummary: string
  hasKb: boolean
}): Promise<RetrieveJudgeResult> {
  const block = `【主题】\n${params.topic.trim()}\n\n【已有材料摘要（来自上传/解析 PDF，可能为空）】\n${params.materialSummary || '（无）'}\n\n【是否已选择知识库】\n${params.hasKb ? '是' : '否'}`
  const raw = await chatJudge('retrieve-judge-outline', block)
  const parsed = parseJudgeJson(raw)
  if (parsed) {
    if (parsed.need_search && parsed.keywords.length > 3) {
      parsed.keywords = parsed.keywords.slice(0, 3)
    }
    if (!parsed.need_search) {
      parsed.keywords = []
      parsed.search_types = []
    }
    return parsed
  }
  return { need_search: false, search_types: [], keywords: [], reason: '判定解析失败，跳过检索' }
}

/** 生成每页内容前：是否检索 + 最多 1 个关键词 */
export async function judgePageRetrieve(params: {
  topic: string
  pageTheme: string
  pageType: string
  outlineSnippet: string
  hasKb: boolean
}): Promise<RetrieveJudgeResult> {
  const block = `【整体主题】\n${params.topic.trim()}\n\n【本页类型】${params.pageType}\n【本页主题】\n${params.pageTheme.trim()}\n\n【大纲上下文】\n${params.outlineSnippet || '（无）'}\n\n【是否已选择知识库】\n${params.hasKb ? '是' : '否'}`
  const raw = await chatJudge('retrieve-judge-page', block)
  const parsed = parseJudgeJson(raw)
  if (parsed) {
    if (parsed.need_search && parsed.keywords.length > 1) {
      parsed.keywords = parsed.keywords.slice(0, 1)
    }
    if (!parsed.need_search) {
      parsed.keywords = []
      parsed.search_types = []
    }
    return parsed
  }
  return { need_search: false, search_types: [], keywords: [], reason: '判定解析失败，跳过检索' }
}

/**
 * 按关键词多次 query，合并后只保留 **score 最高** 的一条文本（无 score 时视为最低分）。
 * candidateTopK：每次检索取前若干条候选，用于在多关键词间比较匹配度。
 */
export async function gatherRagSnippets(
  userId: string,
  kbId: string,
  keywords: string[],
  candidateTopK = 8
): Promise<string> {
  if (!userId || !kbId || keywords.length === 0) return ''
  type Cand = { text: string; score: number }
  const candidates: Cand[] = []
  for (const kw of keywords) {
    if (!kw.trim()) continue
    try {
      const res = await queryKnowledgeBase(userId, kbId, kw.trim(), candidateTopK)
      for (const hit of res.results || []) {
        const t = (hit.text || '').trim()
        if (!t) continue
        const s =
          typeof hit.score === 'number' && !Number.isNaN(hit.score)
            ? hit.score
            : Number.NEGATIVE_INFINITY
        candidates.push({ text: t, score: s })
      }
    } catch {
      /* 单条失败不阻断 */
    }
  }
  if (candidates.length === 0) return ''
  const byText = new Map<string, number>()
  for (const c of candidates) {
    const prev = byText.get(c.text)
    if (prev === undefined || c.score > prev) byText.set(c.text, c.score)
  }
  let bestText = ''
  let bestScore = Number.NEGATIVE_INFINITY
  for (const [text, score] of byText) {
    if (score > bestScore) {
      bestScore = score
      bestText = text
    }
  }
  if (!bestText) {
    bestText = candidates[0]?.text ?? ''
  }
  return '【知识库检索参考（RAG）】\n' + `（匹配度最高）${bestText}`
}

/** 外部检索：优先 web-search-service（OpenSearch）；失败或未返回内容时回退 LLM 常识摘要 */
export async function gatherWebSummary(keywords: string[]): Promise<string> {
  if (keywords.length === 0) return ''
  const q = keywords.join('；')
  try {
    const ws = await queryWebSearch(q, {
      topK: Math.min(Math.max(keywords.length, 1), 5),
      contentType: 'summary'
    })
    const block = formatWebSearchForWorkshop(ws)
    if (block) return block
  } catch {
    /* 服务未启动、未配置 token 或 OpenSearch 异常时回退 */
  }
  const res = await request.post<LlmResult>(
    '/llm/chat',
    { messages: [{ role: 'user', content: `检索关键词：${q}` }] },
    { params: { provider: 'qwen', promptKey: 'retrieve-web-summary' }, timeout: 60000 }
  )
  const data = res.data
  if (data?.code !== 200) return ''
  const text = getContentFromResponse(data)?.trim() ?? ''
  if (!text) return ''
  return '【外部检索参考摘要（模型常识占位，非实时联网）】\n' + text
}
