import request from './request'

/** 版式项：来自 template-service resources/examples/layouts.json */
export interface ExampleLayoutItem {
  code: string
  name: string
  description?: string
  file?: string
  tags?: string[]
}

interface ListLayoutsRes {
  code: number
  message: string
  data: ExampleLayoutItem[]
}

export const templateApi = {
  listLayouts() {
    return request.get<ListLayoutsRes>('/template/layouts')
  },
  getLayout(code: string) {
    return request.get<{ code: number; message: string; data: ExampleLayoutItem }>(`/template/layouts/${code}`)
  },
  /** 版式 HTML 预览地址（需与 template-service 同源或代理） */
  getPreviewUrl(code: string): string {
    return `/api/template/layouts/${code}/preview`
  },
  /** 拉取版式 HTML 内容（用于弹窗 srcdoc 渲染，走同一代理） */
  getPreviewHtml(code: string) {
    return request.get<string>(`/template/layouts/${code}/preview`, { responseType: 'text' })
  },
  /** 更新版式名称和/或描述 */
  updateLayoutMeta(code: string, payload: { name?: string; description?: string }) {
    return request.patch<{ code: number; message: string }>(`/template/layouts/${code}`, payload)
  },
  /** 新增版式（写入 template-service data 目录） */
  createLayout(payload: { name: string; description?: string; tags?: string[]; html: string }) {
    return request.post<{ code: number; message: string; data: ExampleLayoutItem }>(`/template/layouts`, payload)
  },
  /** 删除版式 */
  deleteLayout(code: string) {
    return request.delete<{ code: number; message: string }>(`/template/layouts/${code}`)
  }
}
