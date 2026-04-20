<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { templateApi, type ExampleLayoutItem } from '@/api/template'

const router = useRouter()
const route = useRoute()

const layouts = ref<ExampleLayoutItem[]>([])
const loading = ref(false)
const errMsg = ref('')
const showPreview = ref(false)
const previewItem = ref<ExampleLayoutItem | null>(null)
const previewHtml = ref('')
const previewLoading = ref(false)
const previewError = ref('')

const showEditModal = ref(false)
const editLayout = ref<ExampleLayoutItem | null>(null)
const editName = ref('')
const editDesc = ref('')
const editSaving = ref(false)
const editError = ref('')

async function loadLayouts() {
  loading.value = true
  errMsg.value = ''
  try {
    const res = await templateApi.listLayouts()
    if (res.data?.code === 200 && res.data?.data) {
      layouts.value = res.data.data
    } else {
      errMsg.value = res.data?.message || '加载失败'
      layouts.value = []
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } }; message?: string }
    errMsg.value = ax.response?.data?.message || ax.message || '网络错误'
    layouts.value = []
  } finally {
    loading.value = false
  }

  // 如果带了 preview=code，则自动打开预览一次
  const previewCode = String(route.query.preview ?? '')
  if (previewCode) {
    const hit = layouts.value.find(x => String(x.code) === previewCode)
    if (hit) {
      void openPreview(hit)
      // 清理 query，避免刷新反复弹窗
      void router.replace({ name: 'layout-manage-home', query: {} })
    }
  }
}

function previewUrl(item: ExampleLayoutItem) {
  return templateApi.getPreviewUrl(item.code)
}

async function openPreview(item: ExampleLayoutItem) {
  previewItem.value = item
  previewHtml.value = ''
  previewError.value = ''
  showPreview.value = true
  previewLoading.value = true
  try {
    const res = await templateApi.getPreviewHtml(item.code)
    const html = typeof res.data === 'string' ? res.data : ''
    if (!html.trim()) {
      previewError.value = '预览内容为空'
      return
    }
    if (html.includes('预览加载失败') || html.includes('版式不存在') || html.includes('预览文件不存在') || html.includes('版式未配置')) {
      const match = html.match(/<p[^>]*>([^<]+)<\/p>/)
      previewError.value = match?.[1]?.trim() ?? '服务器返回错误，请确认 template-service 已启动且端口 8086 可访问'
      return
    }
    previewHtml.value = html
  } catch (e: unknown) {
    const ax = e as { response?: { data?: unknown }; message?: string }
    previewError.value = ax.message || (ax.response?.data as string) || '加载预览失败'
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  showPreview.value = false
  previewItem.value = null
  previewHtml.value = ''
  previewError.value = ''
}

function openEdit(item: ExampleLayoutItem) {
  editLayout.value = item
  editName.value = item.name ?? ''
  editDesc.value = item.description ?? ''
  editError.value = ''
  showEditModal.value = true
}

function closeEdit() {
  showEditModal.value = false
  editLayout.value = null
  editName.value = ''
  editDesc.value = ''
  editError.value = ''
}

async function saveEdit() {
  const item = editLayout.value
  if (!item) return
  const code = item.code ?? (item as { id?: string }).id
  if (!code || typeof code !== 'string') {
    editError.value = '版式数据异常（缺少编号），请刷新列表后重试'
    return
  }
  editError.value = ''
  editSaving.value = true
  try {
    const res = await templateApi.updateLayoutMeta(code, {
      name: editName.value.trim() || undefined,
      description: editDesc.value.trim() || undefined
    })
    const data = res.data ?? res
    if ((data as { code?: number }).code === 200) {
      await loadLayouts()
      closeEdit()
    } else {
      editError.value = (data as { message?: string }).message ?? '保存失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } }; message?: string }
    editError.value = ax.response?.data?.message || ax.message || '保存失败'
  } finally {
    editSaving.value = false
  }
}

async function deleteLayout(item: ExampleLayoutItem) {
  const code = item.code
  if (!code) return
  const ok = window.confirm(`确认删除版式「${item.name}」吗？此操作会从版式库移除，并尝试删除对应 HTML 文件。`)
  if (!ok) return
  try {
    const res = await templateApi.deleteLayout(code)
    const data = res.data ?? res
    if ((data as { code?: number }).code === 200) {
      await loadLayouts()
    } else {
      errMsg.value = (data as { message?: string }).message ?? '删除失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } }; message?: string }
    errMsg.value = ax.response?.data?.message || ax.message || '删除失败'
  }
}

onMounted(() => {
  void loadLayouts()
})

function goAddLayout() {
  void router.push({ name: 'layout-add' })
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <div class="head-left">
          <h1 class="card-title">版式管理</h1>
          <p class="card-desc">管理版式原子库、组件规范与版式模板</p>
        </div>
        <div class="head-right">
          <button type="button" class="primary-btn" @click="goAddLayout">➕ 添加版式</button>
        </div>
      </div>

      <p v-if="errMsg" class="msg error">{{ errMsg }}</p>

      <div v-if="loading" class="loading">加载中…</div>

      <div v-else class="list-wrap">
        <table class="layout-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="layouts.length === 0">
              <td colspan="3" class="empty">暂无版式，请检查 template-service 的 resources/examples/layouts.json</td>
            </tr>
            <tr v-for="item in layouts" :key="item.code">
              <td class="name">{{ item.name }}</td>
              <td class="desc">{{ item.description || '—' }}</td>
              <td class="actions">
                <button type="button" class="link-btn" @click="openEdit(item)">编辑</button>
                <button type="button" class="link-btn" @click="openPreview(item)">查看 HTML</button>
                <button type="button" class="link-btn danger" @click="deleteLayout(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 预览弹窗：iframe 渲染版式 HTML -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showPreview && previewItem" class="modal-mask" @click.self="closePreview">
          <div class="modal-box">
            <div class="modal-head">
              <span class="modal-title">{{ previewItem.name }}</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closePreview">×</button>
            </div>
            <div class="modal-body">
              <div v-if="previewLoading" class="preview-loading">加载中…</div>
              <p v-else-if="previewError" class="preview-error">{{ previewError }}</p>
              <iframe
                v-else-if="previewHtml"
                :srcdoc="previewHtml"
                class="preview-iframe"
                title="版式预览"
                sandbox="allow-same-origin allow-scripts"
              />
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 编辑名称与描述弹窗 -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showEditModal && editLayout" class="modal-mask" @click.self="closeEdit">
          <div class="modal-box edit-modal">
            <div class="modal-head">
              <span class="modal-title">编辑版式 — {{ editLayout.name }}</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closeEdit">×</button>
            </div>
            <div class="modal-body">
              <div class="form-row">
                <label>名称</label>
                <input v-model="editName" type="text" class="edit-input" placeholder="版式名称" />
              </div>
              <div class="form-row">
                <label>描述</label>
                <textarea v-model="editDesc" class="edit-input edit-textarea" rows="3" placeholder="版式描述" />
              </div>
              <p v-if="editError" class="edit-error">{{ editError }}</p>
              <div class="modal-footer">
                <button type="button" class="btn-primary" :disabled="editSaving" @click="saveEdit">
                  {{ editSaving ? '保存中…' : '保存' }}
                </button>
                <button type="button" class="btn-ghost" @click="closeEdit">取消</button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

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

.card-head {
  margin-bottom: 24px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.head-left {
  min-width: 0;
}
.head-right {
  flex: 0 0 auto;
}

.primary-btn {
  appearance: none;
  border: 0;
  border-radius: 10px;
  padding: 10px 14px;
  font-weight: 700;
  cursor: pointer;
  color: #fff;
  background: var(--vt-c-indigo);
  box-shadow: 0 10px 18px rgba(79, 70, 229, 0.22);
}
.primary-btn:hover {
  filter: brightness(1.03);
}
.primary-btn:active {
  transform: translateY(1px);
}

.card-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-heading);
  margin: 0 0 6px;
}

.card-desc {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin: 0;
}

.msg {
  margin: 0 0 16px;
  font-size: 0.9rem;
}
.msg.error {
  color: var(--vt-c-red);
}

.loading {
  padding: 24px;
  text-align: center;
  color: var(--color-text-muted);
}

.list-wrap {
  flex: 1 1 0;
  min-height: 0;
  overflow: auto;
}

.layout-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.95rem;
}

.layout-table th,
.layout-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.layout-table th {
  font-weight: 600;
  color: var(--color-heading);
  background: var(--color-background-mute);
}

.layout-table tbody tr:hover {
  background: var(--color-background-mute);
}

.name {
  font-weight: 600;
  color: var(--color-heading);
}

.link-btn {
  color: var(--primary);
  background: none;
  border: none;
  font-size: 0.9rem;
  cursor: pointer;
  padding: 0;
}
.link-btn:hover {
  text-decoration: underline;
}
.link-btn.danger {
  color: var(--vt-c-red);
}

.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

/* 编辑弹窗 */
.edit-modal {
  width: 100%;
  max-width: 480px;
  height: auto;
  max-height: 90vh;
}
.edit-modal .modal-body {
  background: var(--color-background);
  align-items: stretch;
  justify-content: flex-start;
}
.form-row {
  margin-bottom: 14px;
}
.form-row label {
  display: block;
  margin-bottom: 4px;
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--color-heading);
}
.edit-input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.95rem;
}
.edit-textarea {
  resize: vertical;
  min-height: 60px;
}
.edit-error {
  margin: 0 0 12px;
  font-size: 0.9rem;
  color: var(--vt-c-red);
}
.edit-modal .modal-footer {
  margin-top: 8px;
  display: flex;
  gap: 10px;
}
.btn-primary {
  padding: 8px 16px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: var(--radius);
  font-size: 0.9rem;
  cursor: pointer;
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.btn-ghost {
  padding: 8px 16px;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.9rem;
  cursor: pointer;
}

/* 预览弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 24px;
}
.modal-box {
  background: var(--color-background);
  border-radius: var(--radius-lg);
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  max-width: 100%;
  max-height: 100%;
  width: 1000px;
  height: 90vh;
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.modal-title {
  font-weight: 600;
  color: var(--color-heading);
}
.modal-close {
  width: 32px;
  height: 32px;
  border: none;
  background: var(--color-background-mute);
  border-radius: var(--radius-md);
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted);
}
.modal-close:hover {
  background: var(--color-border);
  color: var(--color-text);
}
.modal-body {
  flex: 1;
  min-height: 0;
  padding: 16px;
  background: #f0f0f0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.preview-loading,
.preview-error {
  padding: 24px;
  color: var(--color-text-muted);
}
.preview-error {
  color: var(--vt-c-red);
}
.preview-iframe {
  width: 100%;
  height: 100%;
  min-height: 400px;
  border: none;
  border-radius: var(--radius-md);
  background: #fff;
}
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-active .modal-box,
.modal-leave-active .modal-box {
  transition: transform 0.2s ease;
}
.modal-enter-from .modal-box,
.modal-leave-to .modal-box {
  transform: scale(0.95);
}

.desc {
  max-width: 280px;
  color: var(--color-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.date {
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.tag {
  display: inline-block;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  background: var(--primary-light);
  color: var(--primary);
  font-size: 0.85rem;
}

.empty {
  color: var(--color-text-muted);
  text-align: center;
  padding: 32px !important;
}
</style>
