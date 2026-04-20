import request from './request'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface ComponentItem {
  id: string
  userId: string
  name: string
  description: string
  htmlFile: string
  createdAt: string
  updatedAt: string
}

/** 调用 component-service：根据描述生成组件 HTML（不落盘） */
export async function generateComponentHtml(description: string): Promise<string> {
  const res = await request.post<ApiResult<{ html: string }>>(
    '/component/generate',
    { description: description.trim() },
    { timeout: 90000 }
  )
  const data = res.data
  if (data?.code !== 200) throw new Error(data?.message || '生成失败')
  return stripMarkdownCodeFence(data.data?.html || '').trim()
}

/** 调用 component-service：保存组件（落盘到 component-service/Userdata/用户ID/） */
export async function saveComponent(params: {
  userId: string
  name: string
  description: string
  html: string
}): Promise<ComponentItem> {
  const res = await request.post<ApiResult<ComponentItem>>('/component/save', params, { timeout: 30000 })
  const data = res.data
  if (data?.code !== 200 || !data.data) throw new Error(data?.message || '保存失败')
  return data.data
}

export async function listComponents(userId: string): Promise<ComponentItem[]> {
  const res = await request.get<ApiResult<ComponentItem[]>>('/component/list', { params: { userId }, timeout: 15000 })
  const data = res.data
  if (data?.code !== 200) throw new Error(data?.message || '加载失败')
  return Array.isArray(data.data) ? data.data : []
}

export async function deleteComponent(userId: string, id: string): Promise<boolean> {
  const res = await request.delete<ApiResult<boolean>>('/component/delete', { params: { userId, id }, timeout: 15000 })
  const data = res.data
  if (data?.code !== 200) throw new Error(data?.message || '删除失败')
  return !!data.data
}

/** 去掉 AI 可能返回的 Markdown 代码块包裹（如 ```html ... ```） */
export function stripMarkdownCodeFence(s: string): string {
  const raw = String(s ?? '')
  // 去掉首尾 ```xxx
  let out = raw.replace(/^```(?:html|xml)?\s*/i, '').replace(/\s*```$/i, '')
  // 如果仍包含多段 fence，尽量取中间
  if (out.includes('```')) {
    out = out.replace(/```[\s\S]*?```/g, (m) => m.replace(/^```(?:html|xml)?\s*/i, '').replace(/\s*```$/i, ''))
  }
  return out.trim()
}

