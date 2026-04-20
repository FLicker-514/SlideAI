/**
 * 大纲服务（outline-service）：制作工坊「形成大纲」请求 outline-service（Java），与第一步（主题 + 参考文档）衔接。
 * 仅与 AI 相关的部分由 Java 调用 Python outline_service 执行。
 */
import axios from 'axios'

const outlineBaseURL =
  (import.meta.env.VITE_OUTLINE_SERVICE_URL as string) || 'http://127.0.0.1:8083'

const outlineRequest = axios.create({
  baseURL: outlineBaseURL,
  timeout: 120000,
  headers: { 'Content-Type': 'application/json' }
})

export interface OutlineRequest {
  topic: string
  documentContents?: string[]
  ragContent?: string
  externalContent?: string
}

/** Java Result<OutlineResponse> 形状，便于 getContentFromResponse(res) 使用 */
export interface OutlineResult {
  code?: number
  message: string
  data?: { content?: string }
}

/**
 * 制作工坊「形成大纲」：请求 outline-api（Java），主题 + 可选参考文档(content.md)。
 */
export function generateOutlineFromWorkshop(
  topic: string,
  documentContents: string[],
  options?: { ragContent?: string; externalContent?: string }
): Promise<OutlineResult> {
  return outlineRequest
    .post<OutlineResult>('/outline', {
      topic,
      documentContents: documentContents || [],
      language: 'zh',
      extraRequirements: [options?.ragContent, options?.externalContent].filter(Boolean).join('\n') || ''
    })
    .then((res) => res.data)
    .catch((err) => {
      const msg = err.response?.data?.message ?? err.message ?? '请求大纲服务失败'
      return { code: err.response?.status ?? 500, message: msg, data: {} }
    })
}
