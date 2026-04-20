import request from './request'

/** 风格历史项（与 history.json 一致） */
export interface StyleHistoryItem {
  id: string
  name?: string
  descriptionTags?: string[]
  usageScenarioTags?: string[]
}

/** 风格详情（3 页背景 HTML + 字体 JSON + 字体演示 HTML） */
export interface StyleDetail {
  background1: string
  background2: string
  background3: string
  fontJson: string
  /** LLM 生成的字体演示 HTML（一级/二级/三级标题+正文） */
  fontDemoHtml?: string
}

/** 列出用户风格列表 */
export function listStyles(userId: string): Promise<{ code: number; message: string; data: StyleHistoryItem[] }> {
  return request.get('/style/list', { params: { userId } }).then((res) => res.data)
}

/** 获取某风格详情（3 页背景 + font.json） */
export function getStyleDetail(
  userId: string,
  styleId: string
): Promise<{ code: number; message: string; data: StyleDetail }> {
  return request.get('/style/detail', { params: { userId, styleId } }).then((res) => res.data)
}

/** 通过描述生成新风格的返回数据（与从 PPT 上传一致：可编辑名称与标签后保存） */
export interface CreateFromDescriptionData {
  id: string
  name: string
  descriptionTags: string[]
  usageScenarioTags: string[]
  /** 推荐的三级标题字体 */
  heading3Font?: string
  /** 推荐的正文字体 */
  bodyFont?: string
}

/**
 * 通过描述生成新风格：调用文生图（16:9 无文字留白）+ 风格标签，返回 id、name、descriptionTags、usageScenarioTags
 */
export function createStyleFromDescription(params: {
  userId: string
  description: string
  name?: string
}): Promise<{ code: number; message: string; data: CreateFromDescriptionData }> {
  return request
    .post<{ code: number; message: string; data: CreateFromDescriptionData }>('/style/create-from-description', {
      userId: params.userId,
      description: params.description.trim(),
      name: params.name?.trim() || undefined
    }, {
      // 描述生成会触发文生图 + 标签 + 字体分析，耗时可能较长
      timeout: 300000
    })
    .then((res) => res.data)
}

/**
 * 生成新风格：上传 PPT，服务端提取第 1/2/末页背景与字体
 */
export function createStyleFromPpt(params: {
  userId: string
  file: File
  descriptionTags?: string[]
  usageScenarioTags?: string[]
}): Promise<{ code: number; message: string; data: { id: string } }> {
  const form = new FormData()
  form.append('file', params.file)
  form.append('userId', params.userId)
  if (params.descriptionTags?.length)
    form.append('descriptionTags', params.descriptionTags.join(','))
  if (params.usageScenarioTags?.length)
    form.append('usageScenarioTags', params.usageScenarioTags.join(','))
  return request.post('/style/create', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  }).then((res) => res.data)
}

/** 更新风格名称、标签、字体演示 HTML 与 font.json（heading3Font/bodyFont 写入 font.json） */
export function updateStyle(
  userId: string,
  styleId: string,
  payload: { name?: string; descriptionTags?: string[]; usageScenarioTags?: string[]; fontDemoHtml?: string; heading3Font?: string; bodyFont?: string }
): Promise<{ code: number; message: string }> {
  return request
    .patch('/style/update', payload, { params: { userId, styleId } })
    .then((res) => res.data)
}

/** 删除风格：删除对应 HTML、font.json，并从 history.json 中移除 */
export function deleteStyle(userId: string, styleId: string): Promise<{ code: number; message: string }> {
  return request.delete('/style/delete', { params: { userId, styleId } }).then((res) => res.data)
}
