import axios from 'axios'

/** 从 axios / 业务 Error 中取出可读说明（含 Spring Boot 默认错误体） */
export function getApiErrorMessage(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const d = e.response?.data
    if (d && typeof d === 'object') {
      const o = d as Record<string, unknown>
      const pick = (v: unknown) => (typeof v === 'string' && v.trim().length > 0 ? v.trim() : '')
      const m = pick(o.message) || pick(o.detail) || pick(o.error)
      if (m) return m
      const status = e.response?.status
      if (status) return `请求失败（HTTP ${status}）`
    }
    if (e.message && e.message.trim().length > 0) return e.message
    return '网络请求失败'
  }
  if (e instanceof Error && e.message && e.message.trim().length > 0) return e.message
  return '操作失败'
}
