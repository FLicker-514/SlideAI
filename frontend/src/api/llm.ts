import request from './request'

/** LLM 对话消息 */
export interface ChatMessage {
  role: string
  content: string
}

/** 多图时单张图片项（顺序：第1张风格背景，第2~N张本页配图） */
export interface LlmChatRequestImagePart {
  base64: string
  mediaType?: string
}

/** 请求体 */
export interface LlmChatRequest {
  messages: ChatMessage[]
  model?: string
  temperature?: number
  maxTokens?: number
  /** 可选：单张图片 base64，与视觉模型配合使用 */
  imageBase64?: string
  /** 可选：单张图片媒体类型，如 image/png、image/jpeg */
  imageMediaType?: string
  /** 可选：多张图片（第1张风格背景，第2~N张本页配图），非空时优先于 imageBase64 */
  images?: LlmChatRequestImagePart[]
}

/** 响应 data */
export interface LlmChatResponse {
  content: string
  model?: string
}

interface LlmResult {
  code: number
  message: string
  data: LlmChatResponse
}

/**
 * 调用 LLM 生成 PPT 大纲（旧：仅主题，保留兼容）
 * @param userInput 用户输入的主题或需求
 */
export function generateOutline(userInput: string): Promise<LlmResult> {
  return request
    .post<LlmResult>('/llm/chat', {
      messages: [{ role: 'user', content: userInput }]
    } as LlmChatRequest, {
      params: { provider: 'qwen', promptKey: 'ppt-outline' },
      timeout: 60000
    })
    .then((res) => res.data)
}

/**
 * 根据大纲生成整个 PPT 的每页主题（开头、目录、分页 Part、结束页），仅发送一次请求；结果在 llm-service 后端打印
 */
export function generateFullPageThemes(outline: string): Promise<string | null> {
  if (!outline?.trim()) return Promise.resolve(null)
  return request
    .post<LlmResult>('/llm/chat', {
      messages: [{ role: 'user', content: outline.trim() }]
    } as LlmChatRequest, {
      params: { provider: 'qwen', promptKey: 'ppt-full-page-themes' },
      timeout: 60000
    })
    .then((res) => res.data)
    .then((data) => getContentFromResponse(data))
    .catch(() => null)
}

/** AI 分析风格：根据内容页 HTML 返回风格名称、标签、推荐字体与字体演示 HTML */
export interface StyleAnalyzeResult {
  styleName?: string
  descriptionTags?: string[]
  usageScenarioTags?: string[]
  /** Qwen 推荐的三级标题字体名，写入 font.json */
  heading3Font?: string
  /** Qwen 推荐的正文字体名，写入 font.json */
  bodyFont?: string
  /** 适合该模版的字体演示 HTML（一级/二级/三级标题+正文） */
  fontHtml?: string
}

/**
 * 调用 LLM 分析标题页（HTML 或图片），得到风格名称、描述标签、使用场景标签与字体
 * @param content 标题页 HTML 或用户参考文案（当使用图片时仅传用户参考）
 * @param options 可选：标题页图片 base64，与视觉模型配合使用
 */
export function analyzeStyleFromContentPage(
  content: string,
  options?: { imageBase64?: string; imageMediaType?: string }
): Promise<StyleAnalyzeResult> {
  const body: LlmChatRequest = {
    messages: [{ role: 'user', content }]
  }
  if (options?.imageBase64) {
    body.imageBase64 = options.imageBase64
    body.imageMediaType = options.imageMediaType || 'image/png'
  }
  return request
    .post<LlmResult>('/llm/chat', body, {
      params: { provider: 'qwen', promptKey: 'style-analyze' },
      timeout: 180000
    })
    .then((res) => res.data)
    .then((data) => parseStyleAnalyzeContent(getContentFromResponse(data)))
}

/** 从多种可能的响应结构中取出 content 字符串（兼容网关/包装层） */
export function getContentFromResponse(data: unknown): string | null {
  if (!data || typeof data !== 'object') return null
  const o = data as Record<string, unknown>
  if (o.data != null && typeof o.data === 'object') {
    const inner = o.data as Record<string, unknown>
    if (typeof inner.content === 'string') return inner.content
    if (inner.data != null && typeof inner.data === 'object') {
      const inner2 = inner.data as Record<string, unknown>
      if (typeof inner2.content === 'string') return inner2.content
    }
  }
  if (typeof o.content === 'string') return o.content
  return null
}

/** 将 LLM 返回的 content 字符串解析为 StyleAnalyzeResult */
export function parseStyleAnalyzeContent(content: string | null): StyleAnalyzeResult {
  if (!content || typeof content !== 'string') return {}
  const raw = content.trim()
  let jsonStr = raw.replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
  try {
    const parsed = JSON.parse(jsonStr) as StyleAnalyzeResult
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    const start = jsonStr.indexOf('{')
    const end = jsonStr.lastIndexOf('}')
    if (start !== -1 && end !== -1 && end > start) {
      try {
        const parsed = JSON.parse(jsonStr.slice(start, end + 1)) as StyleAnalyzeResult
        return parsed && typeof parsed === 'object' ? parsed : {}
      } catch {
        return {}
      }
    }
  }
  return {}
}

/**
 * 请求 LLM 风格分析并返回原始响应体（供组件自行解析与错误提示）
 */
export function getStyleAnalyzeResponse(
  content: string,
  options?: { imageBase64?: string; imageMediaType?: string }
): Promise<unknown> {
  const body: LlmChatRequest = {
    messages: [{ role: 'user', content }]
  }
  if (options?.imageBase64) {
    body.imageBase64 = options.imageBase64
    body.imageMediaType = options.imageMediaType || 'image/png'
  }
  return request
    .post('/llm/chat', body, {
      params: { provider: 'qwen', promptKey: 'style-analyze' },
      timeout: 300000
    })
    .then((res) => res.data)
}

/** 页面类型与每页内容生成所用 promptKey 的对应关系（与 ppt-full-page-themes 的 type 一致） */
const PAGE_TYPE_PROMPT_KEY: Record<string, string> = {
  封面页: 'ppt-page-cover',
  目录页: 'ppt-page-toc',
  分页: 'ppt-page-part',
  内容页: 'ppt-page-content',
  结束页: 'ppt-page-closing'
}

/**
 * 形成每页内容：根据本页主题 + 参考文档生成该页纯文字内容；按 pageType 选用不同提示词（封面页/目录页/分页/内容页/结束页）
 * @param extraReferenceBlocks 可选：RAG/外部检索等补充参考（已格式化为字符串块）
 */
export function generatePageContent(
  theme: string,
  referenceDocs: string[],
  pageType?: string,
  extraReferenceBlocks?: string[]
): Promise<string> {
  const refBlock =
    referenceDocs.length > 0
      ? referenceDocs
          .map((doc, i) => `【参考文档 ${i + 1}】\n${doc.slice(0, 8000)}`)
          .join('\n\n')
      : '（无参考文档）'
  const extra =
    extraReferenceBlocks && extraReferenceBlocks.length > 0
      ? '\n\n' + extraReferenceBlocks.filter(Boolean).join('\n\n')
      : ''
  const content = `本页主题：${theme}\n\n参考文档：\n${refBlock}${extra}`
  const promptKey = (pageType && PAGE_TYPE_PROMPT_KEY[pageType]) || 'ppt-page-content'
  return request
    .post<LlmResult>('/llm/chat', { messages: [{ role: 'user', content }] } as LlmChatRequest, {
      params: { provider: 'qwen', promptKey },
      timeout: 60000
    })
    .then((res) => res.data)
    .then((data) => getContentFromResponse(data) ?? '')
}

/**
 * 形成每页内容：根据本页文字从候选图片中选出 0～3 张，返回选中的 id 数组
 */
export function selectPageImages(
  pageText: string,
  candidates: Array<{ id: string; description: string }>
): Promise<string[]> {
  if (candidates.length === 0) return Promise.resolve([])
  const listText = candidates.map((c) => `${c.id}: ${c.description || ''}`).join('\n')
  const content = `本页 PPT 内容：\n${pageText}\n\n候选图片（id: 描述）：\n${listText}\n只输出 JSON 数组，如 ["id1","id2"] 或 []。`
  return request
    .post<LlmResult>('/llm/chat', { messages: [{ role: 'user', content }] } as LlmChatRequest, {
      params: { provider: 'qwen', promptKey: 'ppt-page-images' },
      timeout: 30000
    })
    .then((res) => res.data)
    .then((data) => {
      const raw = getContentFromResponse(data) ?? ''
      const trimmed = raw.replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
      const idSet = new Set(candidates.map((c) => c.id))
      try {
        const arr = JSON.parse(trimmed) as unknown
        if (!Array.isArray(arr)) return []
        return arr
          .filter((x): x is string => typeof x === 'string')
          .filter((id) => idSet.has(id))
          .slice(0, 3)
      } catch {
        const start = trimmed.indexOf('[')
        const end = trimmed.lastIndexOf(']')
        if (start !== -1 && end > start) {
          try {
            const arr = JSON.parse(trimmed.slice(start, end + 1)) as unknown
            if (!Array.isArray(arr)) return []
            return arr
              .filter((x): x is string => typeof x === 'string')
              .filter((id) => idSet.has(id))
              .slice(0, 3)
          } catch {
            return []
          }
        }
        return []
      }
    })
}

/** 单页 HTML 生成：传入当页文字、图片描述与尺寸、版式 HTML、风格背景 HTML，AI 返回内容层 HTML 片段 */
export interface GeneratePageHtmlParams {
  theme: string
  textContent: string
  images: Array<{ description: string; width: number; height: number }>
  layoutHtml: string
  styleBackgroundHtml: string
  /** 风格背景图的 base64（有/无配图时均可用：有配图时作为第1张图，无配图时为唯一图）。纯 base64 字符串 */
  backgroundImageBase64?: string
  /** 背景图的媒体类型，如 image/png */
  backgroundImageMediaType?: string
  /** 有配图时：本页配图 base64 数组（与 images 顺序一致），与背景一起以多图形式发送，第1张=背景，第2~N张=配图 */
  contentImageBase64List?: string[]
  /** 按用户意见重生成：修改说明（与 previousPageHtml 一起发给模型） */
  revisionInstructions?: string
  /** 此前已生成的整页 HTML（会截断），供模型在上一版基础上改写 */
  previousPageHtml?: string
}

const MAX_PREVIOUS_PAGE_HTML_CHARS = 8000

/** 与官方多图编辑 API 一致：最多 1–3 张图（图1=背景，图2、图3=配图） */
const MAX_VISION_IMAGES = 3

export function generatePageHtml(params: GeneratePageHtmlParams): Promise<string> {
  const {
    theme,
    textContent,
    images,
    layoutHtml,
    styleBackgroundHtml,
    backgroundImageBase64,
    backgroundImageMediaType,
    contentImageBase64List,
    revisionInstructions,
    previousPageHtml
  } = params
  const hasImages = images.length > 0
  const imagesBlock = '（本页无图片）'

  const content = hasImages
    ? `【本页标题】
${theme}

【本页文字内容】
${textContent}

【版式参考 HTML】
${layoutHtml || '（无版式参考）'}

【本页配图描述】（随附图片中图1=风格背景，图2、图3=本页配图，与 API 多图顺序一致，最多2张配图。）
${images.slice(0, MAX_VISION_IMAGES - 1).map((img, i) => `图${i + 2}：${img.description || ''}`).join('\n')}

请根据上述信息生成本页内容层 HTML 片段，配图使用 <img data-index="i" width="w" height="h" alt="描述" /> 占位，i 从 0 开始对应图2、图3。`
    : `【本页标题】
${theme}

【本页文字内容】
${textContent}

【版式参考 HTML】
${layoutHtml || '（无版式参考）'}

【本页图片】
${imagesBlock}

风格背景以随附图片形式提供。

请根据上述信息生成本页内容层 HTML 片段。`
  let finalContent = content
  if (revisionInstructions?.trim()) {
    const prev = (previousPageHtml || '').trim()
    const truncated =
      prev.length > MAX_PREVIOUS_PAGE_HTML_CHARS
        ? `${prev.slice(0, MAX_PREVIOUS_PAGE_HTML_CHARS)}\n…(已截断，仅保留前 ${MAX_PREVIOUS_PAGE_HTML_CHARS} 字符)`
        : prev
    finalContent += `\n\n【用户修改意见】\n${revisionInstructions.trim()}\n\n【此前已生成的本页完整 HTML（供参考改写；请输出新的「内容层」HTML 片段，保持版式与风格约束一致）】\n${
      truncated || '（尚无历史版本，请仅依据修改意见与上文生成）'
    }`
  }
  const promptKey = hasImages ? 'ppt-page-html-with-images' : 'ppt-page-html-no-images'

  const mediaType = backgroundImageMediaType || 'image/png'
  const requestBody: LlmChatRequest = {
    messages: [{ role: 'user', content: finalContent }]
  }
  if (hasImages && backgroundImageBase64 && contentImageBase64List && contentImageBase64List.length > 0) {
    const contentImages = contentImageBase64List.slice(0, MAX_VISION_IMAGES - 1)
    requestBody.images = [
      { base64: backgroundImageBase64, mediaType },
      ...contentImages.map((b) => ({ base64: b, mediaType: 'image/png' as string }))
    ]
  } else if (hasImages && backgroundImageBase64) {
    requestBody.images = [{ base64: backgroundImageBase64, mediaType }]
  } else if (!hasImages && backgroundImageBase64) {
    requestBody.imageBase64 = backgroundImageBase64
    requestBody.imageMediaType = mediaType
  }

  return request
    .post<LlmResult>('/llm/chat', requestBody, {
      params: { provider: 'qwen', promptKey },
      timeout: 90000
    })
    .then((res) => res.data)
    .then((data) => getContentFromResponse(data) ?? '')
}

/** 版式项（与版式管理一致，用于语义分配） */
export interface LayoutOption {
  code: string
  name: string
  description?: string
}

/**
 * 根据语义为每页分配版式：传入可选版式列表与每页主题/内容，返回每页对应的版式 code 数组
 */
export function assignLayoutsBySemantics(
  layouts: LayoutOption[],
  pages: Array<{ theme: string; textContent: string }>
): Promise<string[]> {
  if (layouts.length === 0 || pages.length === 0) return Promise.resolve(pages.map(() => ''))
  const layoutListText = layouts
    .map((l) => `- ${l.code}: ${l.name}${l.description ? `（${l.description}）` : ''}`)
    .join('\n')
  const pagesListText = pages
    .map((p, i) => `第${i + 1}页 theme: ${p.theme}\n内容摘要: ${(p.textContent || '').slice(0, 300)}`)
    .join('\n\n')
  const content = `可选版式列表：\n${layoutListText}\n\n每页信息：\n${pagesListText}\n\n请为上述每一页选一个最合适的版式 code，只输出 JSON 数组，每项为可选版式列表中的 code（固定长度 id），如 ["a7f2k9m1","e2x8y0z5","d9u3v7w4"]，长度等于页数。`
  const codeSet = new Set(layouts.map((l) => l.code))
  return request
    .post<LlmResult>('/llm/chat', { messages: [{ role: 'user', content }] } as LlmChatRequest, {
      params: { provider: 'qwen', promptKey: 'ppt-assign-layouts' },
      timeout: 60000
    })
    .then((res) => res.data)
    .then((data) => {
      const raw = getContentFromResponse(data) ?? ''
      const trimmed = raw.replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
      try {
        const arr = JSON.parse(trimmed) as unknown
        if (!Array.isArray(arr) || arr.length !== pages.length) return pages.map(() => '')
        return arr
          .map((x) => (typeof x === 'string' && codeSet.has(x) ? x : ''))
          .slice(0, pages.length)
      } catch {
        const start = trimmed.indexOf('[')
        const end = trimmed.lastIndexOf(']')
        if (start !== -1 && end > start) {
          try {
            const arr = JSON.parse(trimmed.slice(start, end + 1)) as unknown
            if (!Array.isArray(arr) || arr.length !== pages.length) return pages.map(() => '')
            return arr
              .map((x) => (typeof x === 'string' && codeSet.has(x) ? x : ''))
              .slice(0, pages.length)
          } catch {
            return pages.map(() => '')
          }
        }
        return pages.map(() => '')
      }
    })
    .catch(() => pages.map(() => ''))
}
