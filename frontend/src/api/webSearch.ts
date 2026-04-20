import request from './request'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface WebSearchItemDto {
  title?: string
  content?: string
  snippet?: string
  link?: string
  publishedTime?: string
}

export interface WebSearchQueryResponseDto {
  query: string
  items: WebSearchItemDto[]
  raw?: unknown
}

/**
 * 调用 web-search-service（阿里云 OpenSearch Web Search），与 scripts/外部检索测试 请求体一致。
 */
export async function queryWebSearch(
  query: string,
  options?: { topK?: number; contentType?: 'summary' | 'snippet'; queryRewrite?: boolean }
): Promise<WebSearchQueryResponseDto> {
  const res = await request.post<ApiResult<WebSearchQueryResponseDto>>(
    '/web-search/query',
    {
      query: query.trim(),
      top_k: options?.topK ?? 3,
      content_type: options?.contentType ?? 'summary',
      query_rewrite: options?.queryRewrite ?? false
    },
    { timeout: 90000 }
  )
  const data = res.data
  if (data?.code !== 200 || !data.data) {
    throw new Error(data?.message || '外部检索失败')
  }
  return data.data
}

/** 拼成制作工坊用的参考文本块 */
export function formatWebSearchForWorkshop(resp: WebSearchQueryResponseDto): string {
  const items = resp.items || []
  if (items.length === 0) return ''
  const parts: string[] = []
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    const body = (it.content || it.snippet || '').trim()
    if (!body) continue
    const title = (it.title || '').trim()
    parts.push(title ? `（${title}）${body}` : body)
  }
  if (parts.length === 0) return ''
  return (
    '【外部检索参考（OpenSearch Web Search）】\n' +
    parts.map((p, idx) => `（结果${idx + 1}）${p}`).join('\n\n')
  )
}
