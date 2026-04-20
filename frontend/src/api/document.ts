import request from './request'

/** parseStatus: 0=未解析，1=解析中，2=已解析 */
export interface PdfEntry {
  fileId: string
  pdfFileName: string | null
  parseStatus: 0 | 1 | 2
}

/** 上传 PDF；parse 为 true 时解析并生成 content.md、images */
export function uploadPdf(
  userId: string,
  file: File,
  parse: boolean
): Promise<{ code: number; message: string; data: { fileId: string; parsed: boolean } }> {
  const form = new FormData()
  form.append('file', file)
  form.append('userId', userId)
  form.append('parse', String(parse))
  return request
    .post('/document/upload/pdf', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    })
    .then((res) => res.data)
}

export interface ImageEntry {
  imageId: string
  fileName: string
  description: string
}

/** 上传图片（每次一张）；须填描述或勾选 AI 自动生成 */
export function uploadImage(
  userId: string,
  file: File,
  options: { description?: string; autoCaption?: boolean }
): Promise<{ code: number; message: string; data: { imageId: string } }> {
  const form = new FormData()
  form.append('file', file)
  form.append('userId', userId)
  form.append('description', options.description ?? '')
  form.append('autoCaption', String(!!options.autoCaption))
  return request
    .post('/document/upload/image', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 60000
    })
    .then((res) => res.data)
}

/** 列出用户 PDF */
export function listPdfs(userId: string): Promise<{ code: number; message: string; data: PdfEntry[] }> {
  return request.get('/document/list/pdf', { params: { userId } }).then((res) => res.data)
}

/** 列出用户图片（imageId、fileName、description） */
export function listImages(userId: string): Promise<{ code: number; message: string; data: ImageEntry[] }> {
  return request.get('/document/list/image', { params: { userId } }).then((res) => res.data)
}

/** 获取图片文件 Blob（用于预览） */
export function getImageFileBlob(userId: string, imageId: string): Promise<Blob> {
  return request
    .get('/document/image/file', { params: { userId, imageId }, responseType: 'blob' })
    .then((res) => res.data as Blob)
}

/** 删除图片 */
export function deleteImage(userId: string, imageId: string): Promise<{ code: number; message: string }> {
  return request.delete('/document/image', { params: { userId, imageId } }).then((res) => res.data)
}

/** 更新图片描述 */
export function updateImageDescription(
  userId: string,
  imageId: string,
  description: string
): Promise<{ code: number; message: string }> {
  return request
    .put('/document/image/description', null, { params: { userId, imageId, description } })
    .then((res) => res.data)
}

/** PDF 内图片项（来自 images/info.json） */
export interface PdfImageEntry {
  id: string
  description: string
}

/** 列出指定 PDF 解析出的图片（id、description） */
export function getPdfImages(
  userId: string,
  fileId: string
): Promise<{ code: number; message: string; data: PdfImageEntry[] }> {
  return request.get('/document/pdf/images', { params: { userId, fileId } }).then((res) => res.data)
}

/** 获取 PDF 内单张图片文件 Blob（用于预览） */
export function getPdfImageFileBlob(
  userId: string,
  fileId: string,
  imageId: string
): Promise<Blob> {
  return request
    .get('/document/pdf/image/file', { params: { userId, fileId, imageId }, responseType: 'blob' })
    .then((res) => res.data as Blob)
}

/** 获取 PDF 解析内容 content.md */
export function getPdfContent(
  userId: string,
  fileId: string
): Promise<{ code: number; message: string; data: { content: string } }> {
  return request.get('/document/pdf/content', { params: { userId, fileId } }).then((res) => res.data)
}

/** 获取 PDF 文件 Blob（用于预览，带鉴权） */
export function getPdfFileBlob(userId: string, fileId: string): Promise<Blob> {
  return request
    .get('/document/pdf/file', { params: { userId, fileId }, responseType: 'blob' })
    .then((res) => res.data as Blob)
}

/** 对已上传的 PDF 执行解析 */
export function parsePdf(userId: string, fileId: string): Promise<{ code: number; message: string }> {
  return request
    .post('/document/pdf/parse', null, { params: { userId, fileId } })
    .then((res) => res.data)
}

/** 删除 PDF（同时删除本地目录及其中所有文件） */
export function deletePdf(userId: string, fileId: string): Promise<{ code: number; message: string }> {
  return request
    .delete('/document/pdf', { params: { userId, fileId } })
    .then((res) => res.data)
}

// --------------- PPT 创作（Userdata/{userId}/ppt/{pptId}/） ---------------

export interface PptListItem {
  pptId: string
  topic: string
  currentStep: number
  updatedAt: string
}

export interface PptProcess {
  currentStep: number
  topic: string
  updatedAt: string
}

export interface PptUpload {
  pdfIds: string[]
  imageIds: string[]
}

export interface PptOutline {
  content: string
}

export interface PptPageItem {
  theme: string
  textContent: string
  imageIds: string[]
  pageType?: string
}

export interface PptPageContents {
  pages: PptPageItem[]
}

export interface PptLayoutCodes {
  codes: string[]
}

export interface PptStyle {
  styleId: string
}

/** 逐页生成单页（generatedPages.json 一项，仅持久化 id 与 html） */
export interface PptGeneratedPageItem {
  id: number
  html: string
}

export interface PptGeneratedPages {
  pages: PptGeneratedPageItem[]
}

export interface PptState {
  process: PptProcess
  upload: PptUpload
  outline: PptOutline
  pageContents: PptPageContents
  layoutCodes: PptLayoutCodes
  style: PptStyle
  generatedPages?: PptGeneratedPages
}

/** 创建一份 PPT 创作（内容上传完成后） */
export function createPpt(
  userId: string,
  body: { topic: string; pdfIds: string[]; imageIds: string[] }
): Promise<{ code: number; message: string; data: { pptId: string } }> {
  return request.post('/document/ppt', body, { params: { userId } }).then((res) => res.data)
}

/** 列出该用户所有 PPT 创作 */
export function listPpts(
  userId: string
): Promise<{ code: number; message: string; data: PptListItem[] }> {
  return request.get('/document/ppt/list', { params: { userId } }).then((res) => res.data)
}

/** 获取一份 PPT 创作的完整状态 */
export function getPpt(
  userId: string,
  pptId: string
): Promise<{ code: number; message: string; data: PptState }> {
  return request.get(`/document/ppt/${pptId}`, { params: { userId } }).then((res) => res.data)
}

/** 更新 process.json */
export function updatePptProcess(
  userId: string,
  pptId: string,
  body: { currentStep: number; topic?: string }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/process`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 upload.json */
export function updatePptUpload(
  userId: string,
  pptId: string,
  body: { pdfIds: string[]; imageIds: string[] }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/upload`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 outline.json */
export function updatePptOutline(
  userId: string,
  pptId: string,
  body: { content: string }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/outline`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 pageContents.json */
export function updatePptPageContents(
  userId: string,
  pptId: string,
  body: { pages: PptPageItem[] }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/page-contents`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 layoutCodes.json */
export function updatePptLayoutCodes(
  userId: string,
  pptId: string,
  body: { codes: string[] }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/layout-codes`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 style.json */
export function updatePptStyle(
  userId: string,
  pptId: string,
  body: { styleId: string }
): Promise<{ code: number; message: string }> {
  return request.put(`/document/ppt/${pptId}/style`, body, { params: { userId } }).then((res) => res.data)
}

/** 更新 generatedPages.json（逐页生成结果） */
export function updatePptGeneratedPages(
  userId: string,
  pptId: string,
  body: { pages: Array<{ id: number; html: string }> }
): Promise<{ code: number; message: string }> {
  return request
    .put(`/document/ppt/${pptId}/generated-pages`, body, { params: { userId } })
    .then((res) => res.data)
}

/** 删除一份 PPT 创作 */
export function deletePpt(userId: string, pptId: string): Promise<{ code: number; message: string }> {
  return request.delete(`/document/ppt/${pptId}`, { params: { userId } }).then((res) => res.data)
}
