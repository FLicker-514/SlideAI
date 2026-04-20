import request from './request'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface KnowledgeBaseDto {
  id: string
  name: string
  description: string
  pdfFileIds: string[]
  pdfFileNames?: string[]
  createdAt: string
  updatedAt?: string
  chunkCount?: number
  dimension?: number
  embeddingProvider?: string
  vectorStoreReady?: boolean
}

export interface RagHit {
  id?: string
  source_path?: string
  chunk_index?: number
  text?: string
  score?: number
}

export interface RagQueryResult {
  query: string
  top_k: number
  provider: string
  results: RagHit[]
}

export interface RagHealthDto {
  vectorizeScriptOk: boolean
  retrievalScriptOk: boolean
  scriptsDir: string
  pythonPath: string
  projectRoot?: string
  userdataBase?: string
}

export async function ragHealth(): Promise<RagHealthDto> {
  const res = await request.get<ApiResult<RagHealthDto>>('/rag/health')
  const data = res.data
  if (data?.code !== 200 || !data.data) throw new Error(data?.message || 'RAG 服务不可用')
  return data.data
}

export async function listKnowledgeBases(userId: string): Promise<KnowledgeBaseDto[]> {
  const res = await request.get<ApiResult<KnowledgeBaseDto[]>>('/rag/knowledgebases', {
    params: { userId }
  })
  const data = res.data
  if (data?.code !== 200) throw new Error(data?.message || '列表失败')
  return Array.isArray(data.data) ? data.data : []
}

export async function getKnowledgeBase(userId: string, id: string): Promise<KnowledgeBaseDto> {
  const res = await request.get<ApiResult<KnowledgeBaseDto>>(`/rag/knowledgebases/${id}`, {
    params: { userId }
  })
  const data = res.data
  if (data?.code !== 200 || !data.data) throw new Error(data?.message || '获取失败')
  return data.data
}

export async function createKnowledgeBase(body: {
  userId: string
  name: string
  description: string
  pdfFileIds: string[]
}): Promise<KnowledgeBaseDto> {
  const res = await request.post<ApiResult<KnowledgeBaseDto>>('/rag/knowledgebases', body, {
    timeout: 600000
  })
  const data = res.data
  if (data?.code !== 200 || !data.data) throw new Error(data?.message || '创建失败')
  return data.data
}

export async function updateKnowledgeBaseName(
  userId: string,
  id: string,
  name: string
): Promise<KnowledgeBaseDto> {
  const res = await request.post<ApiResult<KnowledgeBaseDto>>(
    `/rag/knowledgebases/${encodeURIComponent(id)}/rename`,
    { userId, name }
  )
  const data = res.data
  if (data?.code !== 200 || data.data == null) {
    throw new Error((data?.message && data.message.trim()) || '重命名失败')
  }
  return data.data
}

export async function deleteKnowledgeBase(userId: string, id: string): Promise<void> {
  const res = await request.delete<ApiResult<null>>(`/rag/knowledgebases/${id}`, {
    params: { userId }
  })
  const data = res.data
  if (data?.code !== 200) throw new Error(data?.message || '删除失败')
}

export async function queryKnowledgeBase(
  userId: string,
  id: string,
  query: string,
  topK = 5
): Promise<RagQueryResult> {
  const res = await request.post<ApiResult<RagQueryResult>>(`/rag/knowledgebases/${id}/query`, {
    userId,
    query,
    topK
  }, { timeout: 120000 })
  const data = res.data
  if (data?.code !== 200 || !data.data) throw new Error(data?.message || '检索失败')
  return data.data
}
