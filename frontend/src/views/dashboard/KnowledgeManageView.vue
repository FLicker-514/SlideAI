<template>
  <div class="page">
    <div class="card">
      <div class="head">
        <div class="head-left">
          <h1 class="card-title">知识库管理</h1>
          <p class="card-desc">
            查看与检索已构建的知识库；新建时仅可选择「文件管理」中<strong>已解析</strong>的 PDF，由 rag-service 调用 RAG 脚本向量化并落盘到 Userdata。
          </p>
        </div>
        <div class="head-right">
          <button
            type="button"
            class="btn-primary"
            :disabled="!userId || parsedPdfs.length === 0"
            @click="openCreateModal"
          >
            新建知识库
          </button>
        </div>
      </div>

      <div v-if="!userId" class="hint">请先登录</div>

      <template v-else>
        <p v-if="ragWarn" class="rag-warn">{{ ragWarn }}</p>
        <p v-if="kbListError" class="rag-warn">{{ kbListError }}</p>
        <p v-if="parsedPdfs.length === 0 && !pdfLoading" class="hint-inline">
          暂无已解析文档。请先到
          <RouterLink class="link" to="/files">文件管理</RouterLink>
          上传 PDF 并完成解析后再创建知识库。
        </p>

        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>来源文档数</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="kb in kbList" :key="kb.id">
                <td class="col-name">{{ kb.name }}</td>
                <td>{{ kb.pdfFileIds?.length ?? 0 }}</td>
                <td class="col-time">{{ formatTime(kb.createdAt) }}</td>
                <td class="col-actions">
                  <button type="button" class="btn-link" @click="openRename(kb)">重命名</button>
                  <button type="button" class="btn-link" @click="openDetail(kb)">详情</button>
                  <button
                    type="button"
                    class="btn-link btn-danger"
                    :disabled="deleteLoading === kb.id"
                    @click="confirmDelete(kb)"
                  >
                    {{ deleteLoading === kb.id ? '删除中…' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="!kbLoading && kbList.length === 0" class="empty">暂无知识库，请点击「新建知识库」。</div>
          <div v-if="kbLoading" class="empty">加载中…</div>
        </div>
      </template>
    </div>

    <!-- 新建 -->
    <Teleport to="body">
      <div v-if="createOpen" class="modal-mask" @click.self="closeCreateModal">
        <div class="modal-box create-modal">
          <div class="modal-head">
            <span class="modal-title">新建知识库</span>
            <button type="button" class="modal-close" aria-label="关闭" @click="closeCreateModal">×</button>
          </div>
          <div class="modal-body">
            <p v-if="parsedPdfs.length === 0" class="error">没有已解析的 PDF，无法创建。</p>
            <template v-else>
              <label class="field">
                <span class="field-label">名称 <span class="req">*</span></span>
                <input v-model="formName" type="text" class="field-input" placeholder="例如：课程讲义知识库" />
              </label>
              <label class="field">
                <span class="field-label">说明</span>
                <textarea
                  v-model="formDesc"
                  class="field-textarea"
                  rows="3"
                  placeholder="可选，用于区分用途"
                />
              </label>
              <div class="field">
                <span class="field-label">来源文档 <span class="req">*</span></span>
                <p class="field-hint">仅列出文件管理中解析状态为「已解析」的 PDF，可多选。</p>
                <div class="pdf-check-list">
                  <label v-for="p in parsedPdfs" :key="p.fileId" class="pdf-check-row">
                    <input v-model="formPdfIds" type="checkbox" :value="p.fileId" />
                    <span class="pdf-name">{{ p.pdfFileName || p.fileId }}</span>
                    <span class="pdf-id">{{ p.fileId }}</span>
                  </label>
                </div>
              </div>
              <p v-if="formError" class="error">{{ formError }}</p>
              <div class="modal-actions">
                <button type="button" class="btn-primary" :disabled="createSaving" @click="submitCreate">
                  {{ createSaving ? '保存中…' : '确定' }}
                </button>
                <button type="button" class="btn-link" @click="closeCreateModal">取消</button>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 详情 -->
    <Teleport to="body">
      <div v-if="detailKb" class="modal-mask" @click.self="detailKb = null">
        <div class="modal-box detail-modal">
          <div class="modal-head">
            <span class="modal-title">{{ detailKb.name }}</span>
            <button type="button" class="modal-close" aria-label="关闭" @click="detailKb = null">×</button>
          </div>
          <div class="modal-body">
            <p v-if="detailKb.description" class="detail-desc">{{ detailKb.description }}</p>
            <p class="detail-meta">
              创建：{{ formatTime(detailKb.createdAt) }}
              <template v-if="detailKb.updatedAt"> · 更新：{{ formatTime(detailKb.updatedAt) }}</template>
              <template v-if="detailKb.chunkCount != null"> · 分块：{{ detailKb.chunkCount }}</template>
              <template v-if="detailKb.embeddingProvider"> · 嵌入：{{ detailKb.embeddingProvider }}</template>
            </p>
            <h3 class="sub-title">来源文档</h3>
            <ul class="source-list">
              <li
                v-for="(fid, idx) in detailKb.pdfFileIds || []"
                :key="fid"
                class="source-item"
              >
                <span class="source-name">{{ sourceLabel(detailKb, fid, idx) }}</span>
                <code class="source-id">{{ fid }}</code>
              </li>
            </ul>
            <h3 class="sub-title">检索试用</h3>
            <div class="query-row">
              <input
                v-model="queryText"
                type="text"
                class="field-input query-input"
                placeholder="输入问题或关键词"
                @keydown.enter.prevent="runQuery"
              />
              <button type="button" class="btn-primary" :disabled="queryLoading || !queryText.trim()" @click="runQuery">
                {{ queryLoading ? '检索中…' : '检索' }}
              </button>
            </div>
            <p v-if="queryError" class="error">{{ queryError }}</p>
            <div v-if="queryResults.length" class="query-results">
              <div v-for="(r, ri) in queryResults" :key="ri" class="query-hit">
                <div class="query-score">相似度 {{ r.score != null ? r.score.toFixed(4) : '—' }}</div>
                <pre class="query-text">{{ r.text }}</pre>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- 重命名 -->
    <Teleport to="body">
      <div v-if="renameOpen" class="modal-mask" @click.self="closeRename">
        <div class="modal-box create-modal">
          <div class="modal-head">
            <span class="modal-title">重命名知识库</span>
            <button type="button" class="modal-close" aria-label="关闭" @click="closeRename">×</button>
          </div>
          <div class="modal-body">
            <label class="field">
              <span class="field-label">名称 <span class="req">*</span></span>
              <input v-model="renameName" type="text" class="field-input" placeholder="知识库名称" />
            </label>
            <p v-if="renameError" class="error">{{ renameError }}</p>
            <div class="modal-actions">
              <button type="button" class="btn-primary" :disabled="renameSaving" @click="submitRename">
                {{ renameSaving ? '保存中…' : '保存' }}
              </button>
              <button type="button" class="btn-link" @click="closeRename">取消</button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { listPdfs, type PdfEntry } from '@/api/document'
import { getApiErrorMessage } from '@/api/httpError'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  updateKnowledgeBaseName,
  getKnowledgeBase,
  listKnowledgeBases,
  queryKnowledgeBase,
  ragHealth,
  type KnowledgeBaseDto,
  type RagHit
} from '@/api/rag'

const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

const pdfList = ref<PdfEntry[]>([])
const pdfLoading = ref(false)
const parsedPdfs = computed(() => pdfList.value.filter((p) => p.parseStatus === 2))

const kbList = ref<KnowledgeBaseDto[]>([])
const kbLoading = ref(false)
const ragWarn = ref('')
const kbListError = ref('')

const pdfNameMap = computed(() => {
  const m = new Map<string, string>()
  for (const p of pdfList.value) {
    m.set(p.fileId, p.pdfFileName || p.fileId)
  }
  return m
})

async function loadPdfs() {
  if (!userId.value) {
    pdfList.value = []
    return
  }
  pdfLoading.value = true
  try {
    const res = await listPdfs(userId.value)
    pdfList.value = Array.isArray(res.data) ? res.data : []
  } catch {
    pdfList.value = []
  } finally {
    pdfLoading.value = false
  }
}

async function loadKbList() {
  if (!userId.value) {
    kbList.value = []
    return
  }
  kbLoading.value = true
  kbListError.value = ''
  try {
    kbList.value = await listKnowledgeBases(userId.value)
  } catch (e: unknown) {
    kbList.value = []
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    kbListError.value = `知识库列表加载失败：${msg || '请确认 rag-service 已启动（8088）'}`
  } finally {
    kbLoading.value = false
  }
}

async function checkRagHealth() {
  ragWarn.value = ''
  try {
    const h = await ragHealth()
    if (!h.vectorizeScriptOk || !h.retrievalScriptOk) {
      ragWarn.value = 'RAG 脚本未就绪：请检查仓库中 scripts/RAG测试 是否完整，并配置 rag.project-root。'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    ragWarn.value = `无法连接 rag-service：${msg || '请启动 rag-service（mvn spring-boot:run -pl rag-service）'}`
  }
}

watch(
  userId,
  (id) => {
    void loadPdfs()
    void checkRagHealth()
    void loadKbList()
  },
  { immediate: true }
)

const createOpen = ref(false)
const formName = ref('')
const formDesc = ref('')
const formPdfIds = ref<string[]>([])
const formError = ref('')
const createSaving = ref(false)

const deleteLoading = ref<string | null>(null)
const detailKb = ref<KnowledgeBaseDto | null>(null)

const queryText = ref('')
const queryLoading = ref(false)
const queryError = ref('')
const queryResults = ref<RagHit[]>([])

const renameOpen = ref(false)
const renameKb = ref<KnowledgeBaseDto | null>(null)
const renameName = ref('')
const renameSaving = ref(false)
const renameError = ref('')

function openRename(kb: KnowledgeBaseDto) {
  renameKb.value = kb
  renameName.value = kb.name
  renameError.value = ''
  renameOpen.value = true
}

function closeRename() {
  renameOpen.value = false
  renameKb.value = null
  renameName.value = ''
  renameError.value = ''
}

async function submitRename() {
  if (!userId.value || !renameKb.value) return
  const name = renameName.value.trim()
  if (!name) {
    renameError.value = '请填写名称'
    return
  }
  renameSaving.value = true
  renameError.value = ''
  try {
    await updateKnowledgeBaseName(userId.value, renameKb.value.id, name)
    if (detailKb.value?.id === renameKb.value.id) {
      try {
        detailKb.value = await getKnowledgeBase(userId.value, renameKb.value.id)
      } catch {
        if (detailKb.value) detailKb.value = { ...detailKb.value, name }
      }
    }
    closeRename()
    await loadKbList()
  } catch (e: unknown) {
    renameError.value = getApiErrorMessage(e)
  } finally {
    renameSaving.value = false
  }
}

async function openCreateModal() {
  formName.value = ''
  formDesc.value = ''
  formPdfIds.value = []
  formError.value = ''
  createOpen.value = true
  await loadPdfs()
}

function closeCreateModal() {
  createOpen.value = false
}

async function submitCreate() {
  formError.value = ''
  if (!userId.value) {
    formError.value = '请先登录'
    return
  }
  const name = formName.value.trim()
  if (!name) {
    formError.value = '请填写名称'
    return
  }
  const allowed = new Set(parsedPdfs.value.map((p) => p.fileId))
  const ids = formPdfIds.value.filter((id) => allowed.has(id))
  if (ids.length === 0) {
    formError.value = '请至少选择一个已解析的 PDF'
    return
  }
  createSaving.value = true
  try {
    await createKnowledgeBase({
      userId: userId.value,
      name,
      description: formDesc.value,
      pdfFileIds: ids
    })
    closeCreateModal()
    await loadKbList()
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    formError.value = msg || '创建失败'
  } finally {
    createSaving.value = false
  }
}

function sourceLabel(kb: KnowledgeBaseDto, fid: string, idx: number) {
  const names = kb.pdfFileNames
  if (names && names[idx]) return names[idx]
  return pdfNameMap.value.get(fid) || '（文档可能已删除或不在列表中）'
}

async function openDetail(kb: KnowledgeBaseDto) {
  queryText.value = ''
  queryError.value = ''
  queryResults.value = []
  if (!userId.value) return
  try {
    detailKb.value = await getKnowledgeBase(userId.value, kb.id)
  } catch {
    detailKb.value = kb
  }
}

async function runQuery() {
  if (!userId.value || !detailKb.value || !queryText.value.trim()) return
  queryLoading.value = true
  queryError.value = ''
  queryResults.value = []
  try {
    const res = await queryKnowledgeBase(userId.value, detailKb.value.id, queryText.value.trim(), 5)
    queryResults.value = res.results || []
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    queryError.value = msg || '检索失败'
  } finally {
    queryLoading.value = false
  }
}

async function confirmDelete(kb: KnowledgeBaseDto) {
  if (!userId.value) return
  if (!confirm(`确定删除知识库「${kb.name}」吗？将删除向量库与索引条目。`)) return
  deleteLoading.value = kb.id
  try {
    await deleteKnowledgeBase(userId.value, kb.id)
    if (detailKb.value?.id === kb.id) detailKb.value = null
    await loadKbList()
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    window.alert(msg || '删除失败')
  } finally {
    deleteLoading.value = null
  }
}

function formatTime(iso: string | undefined) {
  if (!iso) return '—'
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    return d.toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}
</script>

<style scoped>
.page {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.card {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 32px 28px;
  background: var(--color-background-soft);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.head-left {
  flex: 1 1 280px;
  min-width: 0;
}

.head-right {
  flex-shrink: 0;
}

.card-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-heading);
  margin-bottom: 6px;
}

.card-desc {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin: 0;
  line-height: 1.5;
}

.card-desc strong {
  font-weight: 700;
  color: var(--color-heading);
}

.hint {
  color: var(--color-text-muted);
  padding: 16px 0;
}

.rag-warn {
  font-size: 0.9rem;
  color: var(--color-danger, #b45309);
  background: rgba(251, 191, 36, 0.12);
  border: 1px solid rgba(251, 191, 36, 0.5);
  border-radius: var(--radius);
  padding: 10px 12px;
  margin: 0 0 16px;
  line-height: 1.45;
}

.hint-inline {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin: 0 0 16px;
  line-height: 1.5;
}

.link {
  color: var(--primary);
  text-decoration: none;
  font-weight: 600;
}

.link:hover {
  text-decoration: underline;
}

.table-wrap {
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-top: 8px;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  background: var(--color-background);
}

.data-table th,
.data-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.data-table thead th {
  font-weight: 600;
  background: var(--color-background-mute);
}

.col-name {
  font-weight: 600;
  max-width: 200px;
}

.col-time {
  white-space: nowrap;
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.col-actions {
  white-space: nowrap;
}

.btn-primary {
  padding: 8px 16px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: var(--radius);
  cursor: pointer;
  font-weight: 500;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-link {
  background: none;
  border: none;
  color: var(--primary);
  cursor: pointer;
  font-size: 0.9rem;
  margin-right: 8px;
}

.btn-link:last-of-type {
  margin-right: 0;
}

.btn-link:hover:not(:disabled) {
  text-decoration: underline;
}

.btn-link:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-link.btn-danger {
  color: var(--color-danger, #dc2626);
}

.empty {
  padding: 16px;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.error {
  color: var(--color-danger, #dc2626);
  font-size: 0.9rem;
  margin: 8px 0 0;
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-box {
  background: var(--color-background);
  border-radius: var(--radius);
  max-width: 90vw;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.create-modal {
  width: 92vw;
  max-width: 520px;
}

.detail-modal {
  width: 92vw;
  max-width: 560px;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--color-border);
}

.modal-title {
  font-weight: 600;
  font-size: 1.05rem;
}

.modal-close {
  background: none;
  border: none;
  font-size: 1.4rem;
  cursor: pointer;
  color: var(--color-text-muted);
}

.modal-body {
  padding: 18px;
  overflow: auto;
  max-height: 70vh;
}

.field {
  display: block;
  margin-bottom: 14px;
}

.field-label {
  display: block;
  font-size: 0.9rem;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--color-heading);
}

.req {
  color: var(--color-danger, #dc2626);
}

.field-input,
.field-textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.9rem;
}

.field-textarea {
  resize: vertical;
  min-height: 72px;
}

.field-hint {
  font-size: 0.85rem;
  color: var(--color-text-muted);
  margin: 0 0 8px;
}

.pdf-check-list {
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 8px 10px;
  background: var(--color-background);
}

.pdf-check-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--color-border);
  font-size: 0.9rem;
  cursor: pointer;
}

.pdf-check-row:last-child {
  border-bottom: none;
}

.pdf-check-row input {
  margin-top: 3px;
  flex-shrink: 0;
}

.pdf-name {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.pdf-id {
  flex-shrink: 0;
  font-family: monospace;
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.modal-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 8px;
}

.detail-desc {
  margin: 0 0 10px;
  line-height: 1.5;
  color: var(--color-text-muted);
  font-size: 0.95rem;
}

.detail-meta {
  margin: 0 0 16px;
  font-size: 0.85rem;
  color: var(--color-text-muted);
}

.sub-title {
  font-size: 1rem;
  font-weight: 700;
  margin: 0 0 10px;
  color: var(--color-heading);
}

.source-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.source-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border);
  font-size: 0.9rem;
}

.source-item:last-child {
  border-bottom: none;
}

.source-name {
  flex: 1;
  min-width: 0;
}

.source-id {
  font-size: 0.8rem;
  padding: 2px 6px;
  background: var(--color-background-mute);
  border-radius: 4px;
}

.query-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.query-input {
  flex: 1 1 200px;
  min-width: 0;
}

.query-results {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.query-hit {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 10px 12px;
  background: var(--color-background-mute);
}

.query-score {
  font-size: 0.8rem;
  color: var(--color-text-muted);
  margin-bottom: 6px;
}

.query-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 0.88rem;
  line-height: 1.45;
  font-family: inherit;
}
</style>
