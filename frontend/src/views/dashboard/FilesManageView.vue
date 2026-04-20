<template>
  <div class="page">
    <div class="card">
      <h1 class="card-title">文件管理</h1>

      <div v-if="!userId" class="hint">请先登录</div>
      <template v-else>
      <!-- PDF -->
      <section class="section">
        <h2 class="section-title">PDF</h2>
        <div class="upload-row">
          <input
            ref="pdfInputRef"
            type="file"
            accept=".pdf,application/pdf"
            @change="onPdfSelect"
          />
          <label class="checkbox-wrap">
            <input v-model="pdfParse" type="checkbox" />
            <span>上传后解析（生成 content.md 与 images）</span>
          </label>
          <button type="button" class="btn-primary" :disabled="!pdfFile || pdfUploading" @click="submitPdf">
            {{ pdfUploading ? '上传中…' : '上传 PDF' }}
          </button>
        </div>
        <p v-if="pdfError" class="error">{{ pdfError }}</p>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>文件 ID</th>
                <th>文件名</th>
                <th>解析状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in pdfList" :key="item.fileId">
                <td class="col-id">{{ item.fileId }}</td>
                <td>{{ item.pdfFileName || '—' }}</td>
                <td>{{ parseStatusText(item.parseStatus) }}</td>
                <td class="col-actions">
                  <button
                    v-if="item.parseStatus === 0"
                    type="button"
                    class="btn-link"
                    :disabled="parseLoading === item.fileId"
                    @click="parseOnePdf(item.fileId)"
                  >
                    {{ parseLoading === item.fileId ? '解析中…' : '解析' }}
                  </button>
                  <span v-else-if="item.parseStatus === 1" class="status-parsing">解析中…</span>
                  <button
                    type="button"
                    class="btn-link"
                    @click="openPdfPreview(item.fileId)"
                  >
                    预览 PDF
                  </button>
                  <button
                    v-if="item.parseStatus !== 1"
                    type="button"
                    class="btn-link btn-danger"
                    :disabled="deleteLoading === item.fileId"
                    @click="confirmDeletePdf(item)"
                  >
                    {{ deleteLoading === item.fileId ? '删除中…' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="pdfList.length === 0 && !pdfLoading" class="empty">暂无 PDF</div>
        </div>
      </section>

      <!-- 图片（每次一张，须填描述或 AI 生成） -->
      <section class="section">
        <h2 class="section-title">图片</h2>
        <div class="upload-block">
          <input
            ref="imgInputRef"
            type="file"
            accept="image/jpeg,image/png,image/gif,image/webp"
            @change="onImageSelect"
          />
          <div class="image-desc-row">
            <label class="label">图片描述：</label>
            <textarea
              v-model="imageDescription"
              class="desc-textarea"
              placeholder="填写描述，或勾选下方 AI 自动生成"
              rows="2"
            />
          </div>
          <label class="checkbox-wrap">
            <input v-model="imageAutoCaption" type="checkbox" />
            <span>AI 自动生成图片描述</span>
          </label>
          <button
            type="button"
            class="btn-primary"
            :disabled="!imageFile || imageUploading || (!imageDescription.trim() && !imageAutoCaption)"
            @click="submitImage"
          >
            {{ imageUploading ? '上传中…' : '上传图片' }}
          </button>
        </div>
        <p v-if="imageError" class="error">{{ imageError }}</p>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>图片 ID</th>
                <th>文件名</th>
                <th>描述</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in imageList" :key="item.imageId">
                <td class="col-id">{{ item.imageId }}</td>
                <td>{{ item.fileName || '—' }}</td>
                <td class="col-desc">{{ item.description || '—' }}</td>
                <td class="col-actions">
                  <button type="button" class="btn-link" @click="openImagePreview(item.imageId)">预览</button>
                  <button type="button" class="btn-link" @click="openEditImageDesc(item)">编辑描述</button>
                  <button
                    type="button"
                    class="btn-link btn-danger"
                    :disabled="imageDeleteLoading === item.imageId"
                    @click="confirmDeleteImage(item)"
                  >
                    {{ imageDeleteLoading === item.imageId ? '删除中…' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="imageList.length === 0 && !imageListLoading" class="empty">暂无图片</div>
        </div>
        <!-- 编辑描述弹窗 -->
        <Teleport to="body">
          <div v-if="showDescModal" class="modal-mask" @click.self="closeDescModal">
            <div class="modal-box desc-modal">
              <div class="modal-head">
                <span class="modal-title">编辑图片描述</span>
                <button type="button" class="modal-close" aria-label="关闭" @click="closeDescModal">×</button>
              </div>
              <div class="modal-body">
                <textarea
                  v-model="editingDesc"
                  class="desc-textarea"
                  placeholder="输入描述"
                  rows="4"
                />
                <div class="modal-actions">
                  <button type="button" class="btn-primary" :disabled="descSaving" @click="saveImageDescription">
                    {{ descSaving ? '保存中…' : '保存' }}
                  </button>
                  <button type="button" class="btn-link" @click="closeDescModal">取消</button>
                </div>
              </div>
            </div>
          </div>
        </Teleport>
      </section>
    </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  uploadPdf,
  uploadImage,
  listPdfs,
  listImages,
  getPdfFileBlob,
  getImageFileBlob,
  parsePdf,
  deletePdf,
  deleteImage,
  updateImageDescription,
  type PdfEntry,
  type ImageEntry
} from '@/api/document'

const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

const pdfInputRef = ref<HTMLInputElement | null>(null)
const pdfFile = ref<File | null>(null)
const pdfParse = ref(false)
const pdfUploading = ref(false)
const pdfError = ref('')
const pdfList = ref<PdfEntry[]>([])
const pdfLoading = ref(false)

const imgInputRef = ref<HTMLInputElement | null>(null)
const imageFile = ref<File | null>(null)
const imageDescription = ref('')
const imageAutoCaption = ref(false)
const imageUploading = ref(false)
const imageError = ref('')
const imageList = ref<ImageEntry[]>([])
const imageListLoading = ref(false)
const imageDeleteLoading = ref<string | null>(null)
const showDescModal = ref(false)
const editingDesc = ref('')
const editingImageId = ref<string | null>(null)
const descSaving = ref(false)

const parseLoading = ref<string | null>(null)
const deleteLoading = ref<string | null>(null)

function onPdfSelect(e: Event) {
  const input = (e.target as HTMLInputElement).files?.[0]
  pdfFile.value = input && input.type === 'application/pdf' ? input : null
  pdfError.value = ''
}

function onImageSelect(e: Event) {
  const input = (e.target as HTMLInputElement).files?.[0]
  imageFile.value = input && input.type.startsWith('image/') ? input : null
  imageError.value = ''
}

async function loadPdfList() {
  if (!userId.value) return
  pdfLoading.value = true
  try {
    const res = await listPdfs(userId.value)
    pdfList.value = Array.isArray(res.data) ? res.data : []
    if (pdfList.value.some((p) => p.parseStatus === 1)) startParsePolling()
  } catch {
    pdfList.value = []
  } finally {
    pdfLoading.value = false
  }
}

async function loadImageList() {
  if (!userId.value) return
  imageListLoading.value = true
  try {
    const res = await listImages(userId.value)
    imageList.value = Array.isArray(res.data) ? res.data : []
  } catch {
    imageList.value = []
  } finally {
    imageListLoading.value = false
  }
}

async function submitPdf() {
  if (!userId.value || !pdfFile.value) return
  pdfError.value = ''
  pdfUploading.value = true
  try {
    const res = await uploadPdf(userId.value, pdfFile.value, pdfParse.value)
    if (res.code === 200) {
      pdfFile.value = null
      if (pdfInputRef.value) pdfInputRef.value.value = ''
      loadPdfList()
    } else {
      pdfError.value = res.message || '上传失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    pdfError.value = String(msg || '上传失败')
  } finally {
    pdfUploading.value = false
  }
}

async function submitImage() {
  if (!userId.value || !imageFile.value) return
  if (!imageDescription.value.trim() && !imageAutoCaption.value) {
    imageError.value = '请填写图片描述或勾选 AI 自动生成描述'
    return
  }
  imageError.value = ''
  imageUploading.value = true
  try {
    const res = await uploadImage(userId.value, imageFile.value, {
      description: imageDescription.value.trim(),
      autoCaption: imageAutoCaption.value
    })
    if (res.code === 200) {
      imageFile.value = null
      imageDescription.value = ''
      imageAutoCaption.value = false
      if (imgInputRef.value) imgInputRef.value.value = ''
      loadImageList()
    } else {
      imageError.value = res.message || '上传失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    imageError.value = String(msg || '上传失败')
  } finally {
    imageUploading.value = false
  }
}

async function openImagePreview(imageId: string) {
  if (!userId.value) return
  try {
    const blob = await getImageFileBlob(userId.value, imageId)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    imageError.value = '预览失败，请稍后重试'
  }
}

async function confirmDeleteImage(item: ImageEntry) {
  if (!confirm(`确定要删除「${item.fileName || item.imageId}」吗？`)) return
  if (!userId.value) return
  imageDeleteLoading.value = item.imageId
  imageError.value = ''
  try {
    const res = await deleteImage(userId.value, item.imageId)
    if (res.code === 200) loadImageList()
    else imageError.value = res.message || '删除失败'
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    imageError.value = String(msg || '删除失败')
  } finally {
    imageDeleteLoading.value = null
  }
}

function openEditImageDesc(item: ImageEntry) {
  editingImageId.value = item.imageId
  editingDesc.value = item.description || ''
  showDescModal.value = true
}

function closeDescModal() {
  showDescModal.value = false
  editingImageId.value = null
}

async function saveImageDescription() {
  if (!userId.value || !editingImageId.value) return
  descSaving.value = true
  try {
    const res = await updateImageDescription(userId.value, editingImageId.value, editingDesc.value)
    if (res.code === 200) {
      loadImageList()
      closeDescModal()
    } else {
      imageError.value = res.message || '保存失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    imageError.value = String(msg || '保存失败')
  } finally {
    descSaving.value = false
  }
}

let parsePollTimer: ReturnType<typeof setInterval> | null = null

function startParsePolling() {
  if (parsePollTimer) return
  parsePollTimer = setInterval(async () => {
    if (!userId.value) return
    const hasParsing = pdfList.value.some((p) => p.parseStatus === 1)
    if (!hasParsing) {
      if (parsePollTimer) {
        clearInterval(parsePollTimer)
        parsePollTimer = null
      }
      return
    }
    try {
      const res = await listPdfs(userId.value)
      pdfList.value = Array.isArray(res.data) ? res.data : []
    } catch {
      // 忽略轮询错误
    }
  }, 4000)
}

async function parseOnePdf(fileId: string) {
  if (!userId.value) return
  pdfError.value = ''
  parseLoading.value = fileId
  try {
    const res = await parsePdf(userId.value, fileId)
    if (res.code === 200) {
      loadPdfList()
    } else if (res.code === 202) {
      loadPdfList()
      startParsePolling()
    } else if (res.code === 409) {
      pdfError.value = res.message || '该文件正在解析中'
      loadPdfList()
    } else {
      pdfError.value = res.message || '解析启动失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    pdfError.value = String(msg || '解析启动失败')
  } finally {
    parseLoading.value = null
  }
}

async function confirmDeletePdf(item: PdfEntry) {
  const name = item.pdfFileName || item.fileId
  if (!confirm(`确定要删除「${name}」吗？将同时删除本地所有相关文件（PDF、解析结果等）。`)) return
  if (!userId.value) return
  deleteLoading.value = item.fileId
  pdfError.value = ''
  try {
    const res = await deletePdf(userId.value, item.fileId)
    if (res.code === 200) {
      loadPdfList()
    } else {
      pdfError.value = res.message || '删除失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    pdfError.value = String(msg || '删除失败')
  } finally {
    deleteLoading.value = null
  }
}

async function openPdfPreview(fileId: string) {
  if (!userId.value) return
  try {
    const blob = await getPdfFileBlob(userId.value, fileId)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  } catch {
    pdfError.value = '预览失败，请稍后重试'
  }
}

function parseStatusText(status: 0 | 1 | 2 | undefined): string {
  if (status === 1) return '解析中'
  if (status === 2) return '已解析'
  return '未解析'
}

watch(userId, (id) => {
  if (id) {
    loadPdfList()
    loadImageList()
  } else {
    pdfList.value = []
    imageList.value = []
  }
}, { immediate: true })

onUnmounted(() => {
  if (parsePollTimer) {
    clearInterval(parsePollTimer)
    parsePollTimer = null
  }
})
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

.card-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-heading);
  margin-bottom: 6px;
}

.card-desc {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin-bottom: 24px;
}
.hint {
  color: var(--color-text-muted);
  padding: 16px 0;
}
.section {
  margin-bottom: 32px;
}
.section-title {
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--color-heading);
  margin: 0 0 12px;
}
.upload-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.upload-row input[type="file"] {
  font-size: 0.9rem;
}
.upload-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}
.upload-block input[type="file"] { font-size: 0.9rem; }
.image-desc-row { display: flex; align-items: flex-start; gap: 8px; }
.image-desc-row .label { flex-shrink: 0; font-size: 0.9rem; padding-top: 6px; }
.desc-textarea {
  flex: 1;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.9rem;
  resize: vertical;
}
.col-desc { max-width: 280px; word-break: break-word; }
.desc-modal { width: 92vw; max-width: 480px; }
.desc-modal .modal-body { padding: 18px; display: flex; flex-direction: column; gap: 12px; }
.modal-actions { display: flex; gap: 12px; align-items: center; }
.checkbox-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.checkbox-wrap input { width: auto; }
.error { color: var(--color-danger, #dc2626); font-size: 0.9rem; margin: 4px 0; }
.table-wrap { overflow: auto; border: 1px solid var(--color-border); border-radius: var(--radius); margin-top: 8px; }
.data-table { width: 100%; border-collapse: collapse; background: var(--color-background); }
.data-table th, .data-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--color-border); }
.data-table thead th { font-weight: 600; background: var(--color-background-mute); }
.col-id { font-family: monospace; font-size: 0.85rem; }
.col-actions { white-space: nowrap; }
.status-parsing { color: var(--color-text-muted); font-size: 0.9rem; }
.btn-link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 0.9rem; margin-right: 8px; }
.btn-link:last-of-type { margin-right: 0; }
.btn-link:hover:not(:disabled) { text-decoration: underline; }
.btn-link:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-link.btn-danger { color: var(--color-danger, #dc2626); }
.btn-link.btn-danger:hover:not(:disabled) { text-decoration: underline; }
.btn-primary { padding: 8px 16px; background: var(--primary); color: #fff; border: none; border-radius: var(--radius); cursor: pointer; font-weight: 500; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.empty { padding: 16px; color: var(--color-text-muted); font-size: 0.9rem; }
.image-list { list-style: none; padding: 0; margin: 8px 0 0; display: flex; flex-wrap: wrap; gap: 8px; }
.image-item { font-size: 0.9rem; padding: 4px 10px; background: var(--color-background-mute); border-radius: 4px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-box { background: var(--color-background); border-radius: var(--radius); max-width: 90vw; max-height: 90vh; overflow: hidden; display: flex; flex-direction: column; }
.content-modal { width: 92vw; max-width: 720px; max-height: 85vh; }
.content-modal .modal-body { flex: 1; min-height: 0; overflow: auto; padding: 18px; }
.content-pre { white-space: pre-wrap; word-break: break-word; font-size: 0.9rem; margin: 0; }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--color-border); }
.modal-title { font-weight: 600; font-size: 1.05rem; }
.modal-close { background: none; border: none; font-size: 1.4rem; cursor: pointer; color: var(--color-text-muted); }
</style>
