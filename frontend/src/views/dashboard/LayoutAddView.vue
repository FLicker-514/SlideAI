<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { templateApi } from '@/api/template'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

const TAG_OPTIONS = [
  '封面页',
  '目录页',
  '分隔页',
  '结尾页',
  '内容页（有配图）',
  '内容页（无配图）'
] as const

const name = ref('')
const description = ref('')
const tags = ref<string[]>([])
const html = ref('')
const editorMode = ref<'visual' | 'upload'>('visual')
const designerRef = ref<HTMLIFrameElement | null>(null)
const uploadFileName = ref('')
const viewTab = ref<'edit' | 'preview'>('edit')
const designerReady = ref(false)
const designerSrc = ref('')

function refreshDesignerSrc() {
  // 强制刷新 iframe（避免缓存），并把 userId 传给 designer 用于拉取组件库
  const uid = encodeURIComponent(userId.value || '')
  designerSrc.value = `/api/designer/index.html?embed=1&userId=${uid}&v=${Date.now()}`
}

const saving = ref(false)
const errorMsg = ref('')

const canSubmit = computed(() => {
  const baseOk = !!name.value.trim() && !!description.value.trim() && tags.value.length > 0
  if (!baseOk) return false
  // 上传模式下必须有 HTML 内容；可视化编辑模式提交前会自动导出 HTML
  return editorMode.value === 'visual' ? true : !!html.value.trim()
})

function toggleTag(t: string) {
  if (tags.value.includes(t)) tags.value = tags.value.filter(x => x !== t)
  else tags.value = [...tags.value, t]
}

async function submit() {
  // 提交条件：名称必填、描述必填、标签至少 1 个
  if (!name.value.trim()) {
    errorMsg.value = '请填写名称'
    return
  }
  if (!description.value.trim()) {
    errorMsg.value = '请填写描述'
    return
  }
  if (tags.value.length === 0) {
    errorMsg.value = '请选择至少一个标签'
    return
  }

  // 可视化编辑模式下，提交前先自动拉取一次最新 HTML
  if (editorMode.value === 'visual') {
    const ok = await requestExportFromDesigner()
    if (!ok) {
      errorMsg.value = '无法从可视化编辑器获取 HTML，请稍后重试（确认编辑器已加载）'
      return
    }
  } else {
    // 上传模式下必须有 HTML
    if (!html.value.trim()) {
      errorMsg.value = '请上传 HTML 文件或粘贴 HTML 内容'
      return
    }
  }

  saving.value = true
  errorMsg.value = ''
  try {
    const res = await templateApi.createLayout({
      name: name.value.trim(),
      description: description.value.trim() || undefined,
      tags: tags.value,
      html: html.value
    })
    const data = res.data
    if (data?.code === 200 && data.data?.code) {
      // 跳回版式管理，并自动打开预览
      await router.push({ name: 'layout-manage-home', query: { preview: data.data.code } })
    } else {
      errorMsg.value = data?.message || '添加失败'
    }
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } }; message?: string }
    errorMsg.value = ax.response?.data?.message || ax.message || '网络错误'
  } finally {
    saving.value = false
  }
}

function back() {
  void router.push({ name: 'layout-manage-home' })
}

function onDesignerMessage(e: MessageEvent) {
  const data = e.data as unknown
  if (!data || typeof data !== 'object') return
  const msg = data as { type?: string; name?: string; html?: string; requestId?: string }
  if (msg.type === 'DESIGNER_READY') {
    designerReady.value = true
    return
  }
  if (msg.type !== 'DESIGNER_EXPORT') return
  if (pendingExport.value && msg.requestId && msg.requestId === pendingExport.value.id) {
    if (typeof msg.html === 'string') html.value = msg.html
    if (typeof msg.name === 'string' && msg.name.trim() && !name.value.trim()) name.value = msg.name.trim()
    pendingExport.value.resolve(true)
    pendingExport.value = null
    return
  }
  // 兼容旧消息（没有 requestId）
  if (typeof msg.html === 'string') html.value = msg.html
  if (typeof msg.name === 'string' && msg.name.trim() && !name.value.trim()) name.value = msg.name.trim()
}

type PendingExport = { id: string; resolve: (ok: boolean) => void }
const pendingExport = ref<PendingExport | null>(null)

async function requestExportFromDesigner(): Promise<boolean> {
  const win = designerRef.value?.contentWindow
  if (!win) return false
  if (pendingExport.value) return false

  // 等待编辑器就绪（最多 2.5s）
  if (!designerReady.value) {
    const start = Date.now()
    while (!designerReady.value && Date.now() - start < 2500) {
      await new Promise(r => window.setTimeout(r, 120))
    }
  }

  // 发请求并等待回包（自动重试 2 次）
  for (let attempt = 0; attempt < 3; attempt++) {
    const ok = await new Promise<boolean>((resolve) => {
      const id = `${Date.now()}_${Math.random().toString(16).slice(2)}`
      pendingExport.value = { id, resolve }
      win.postMessage({ type: 'REQUEST_EXPORT', requestId: id }, '*')
      window.setTimeout(() => {
        if (pendingExport.value?.id === id) {
          pendingExport.value.resolve(false)
          pendingExport.value = null
        }
      }, 3000)
    })
    if (ok) return true
    await new Promise(r => window.setTimeout(r, 200))
  }
  return false
}

watch(
  () => viewTab.value,
  (tab) => {
    // 切到预览时，如果当前是可视化编辑模式，则自动从编辑器拉一次最新 HTML
    if (tab === 'preview' && editorMode.value === 'visual') {
      void requestExportFromDesigner()
    }
  }
)

async function onUploadHtmlFile(ev: Event) {
  const input = ev.target as HTMLInputElement | null
  const file = input?.files?.[0]
  if (!file) return
  uploadFileName.value = file.name
  try {
    const text = await file.text()
    html.value = text
    // 如果名称为空，尝试用文件名（去后缀）填充
    if (!name.value.trim()) {
      const base = file.name.replace(/\.(html?|txt)$/i, '')
      name.value = base || name.value
    }
    viewTab.value = 'preview'
  } catch (e) {
    uploadFileName.value = ''
    errorMsg.value = '读取文件失败'
  } finally {
    // 允许重复选择同一文件触发 change
    if (input) input.value = ''
  }
}

onMounted(() => {
  refreshDesignerSrc()
  // 允许从“版式设计工具测试”通过 localStorage 注入
  const from = String(route.query.from ?? '')
  if (from === 'designer') {
    const cachedHtml = localStorage.getItem('ppt_template_designer_last_export_html')
    const cachedName = localStorage.getItem('ppt_template_designer_last_export_name')
    if (cachedHtml && !html.value) html.value = cachedHtml
    if (cachedName && !name.value) name.value = cachedName
  }

  window.addEventListener('message', onDesignerMessage)
})

onUnmounted(() => {
  window.removeEventListener('message', onDesignerMessage)
})

watch(
  () => userId.value,
  (uid) => {
    // 登录信息异步到达时，重新加载 designer，确保能拉取该用户的组件库
    if (uid) refreshDesignerSrc()
  }
)
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-head">
        <div class="head-left">
          <h1 class="card-title">添加版式</h1>
        </div>
        <div class="head-right">
          <button type="button" class="ghost-btn" @click="back">返回</button>
          <button type="button" class="primary-btn" :disabled="!canSubmit || saving" @click="submit">
            {{ saving ? '提交中…' : '提交' }}
          </button>
        </div>
      </div>

      <p v-if="errorMsg" class="msg error">{{ errorMsg }}</p>

      <div class="form">
        <!-- 可视化编辑器 iframe：始终挂载，避免切换视图导致未加载/丢失，从而提交时无法导出 -->
        <div
          v-if="editorMode === 'visual'"
          class="designer-wrap"
          :class="{ offscreen: viewTab !== 'edit' }"
        >
          <iframe
            ref="designerRef"
            class="designer-iframe"
            :src="designerSrc"
            title="designer"
            @load="designerReady = true"
          />
        </div>

        <div v-show="viewTab === 'edit'" class="field">
          <label>HTML（完整单文件）</label>
          <div v-if="editorMode === 'upload'" class="upload-wrap">
            <input class="file" type="file" accept=".html,.htm,.txt,text/html,text/plain" @change="onUploadHtmlFile" />
            <div class="upload-hint">
              <div v-if="uploadFileName">已选择：{{ uploadFileName }}</div>
              <div v-else>选择一个单文件 HTML（或 .txt）后，会自动填充到预览与提交内容。</div>
            </div>
            <textarea v-model="html" rows="12" placeholder="（可选）上传后仍可在这里微调 HTML…" />
          </div>
          <div v-else class="upload-hint">在可视化编辑模式下，内容由编辑器自动导出，无需手动粘贴。</div>
        </div>

        <div v-show="viewTab === 'preview'" class="field">
          <label>预览</label>
          <div class="preview">
            <iframe v-if="html.trim()" :srcdoc="html" title="layout-preview" sandbox="allow-scripts allow-same-origin" />
            <div v-else class="preview-empty">生成/上传 HTML 后自动预览</div>
          </div>
        </div>

        <!-- 按你的要求：把“名称/描述/标签/视图”放在可视化编辑（或预览）下面 -->
        <div class="field">
          <label>名称</label>
          <input v-model="name" placeholder="例如：封面（蓝色渐变）" />
        </div>

        <div class="field">
          <label>描述</label>
          <input v-model="description" placeholder="例如：包含主标题、副标题、作者、日期" />
        </div>

        <div class="field">
          <label>标签（可多选）</label>
          <div class="tags">
            <button
              v-for="t in TAG_OPTIONS"
              :key="t"
              type="button"
              class="tag"
              :class="{ active: tags.includes(t) }"
              @click="toggleTag(t)"
            >
              {{ t }}
            </button>
          </div>
        </div>

        <div class="field">
          <label>视图</label>
          <div class="tabs">
            <button type="button" class="tab" :class="{ active: viewTab === 'edit' }" @click="viewTab = 'edit'">编辑</button>
            <button type="button" class="tab" :class="{ active: viewTab === 'preview' }" @click="viewTab = 'preview'">预览</button>
          </div>
        </div>

        <!-- 按你的要求：编辑方式放在最下面 -->
        <div class="field">
          <label>编辑方式（放在底部）</label>
          <div class="mode">
            <button type="button" class="mode-btn" :class="{ active: editorMode === 'visual' }" @click="editorMode = 'visual'">
              可视化编辑
            </button>
            <button type="button" class="mode-btn" :class="{ active: editorMode === 'upload' }" @click="editorMode = 'upload'">
              上传 HTML 文件
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
  height: 100%;
}
.card {
  height: calc(100vh - 32px);
  display: flex;
  flex-direction: column;
  padding: 18px 18px;
  background: var(--color-background-soft);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}
.card-head {
  margin-bottom: 10px;
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
  display: flex;
  align-items: center;
  gap: 10px;
}
.card-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--color-heading);
  margin: 0 0 2px;
}
.card-desc {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin: 0;
}
.msg {
  margin: 0 0 14px;
  font-size: 0.9rem;
}
.msg.error {
  color: var(--vt-c-red);
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
.primary-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  box-shadow: none;
}
.ghost-btn {
  appearance: none;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  padding: 10px 14px;
  font-weight: 700;
  cursor: pointer;
  background: transparent;
  color: var(--color-text);
}

.form {
  overflow: auto;
  padding-right: 4px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
}
label {
  font-size: 12px;
  color: var(--color-text-muted);
}
input,
textarea {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-background);
  color: var(--color-text);
  padding: 10px 12px;
  outline: none;
}
textarea {
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.tag {
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text);
  border-radius: 999px;
  padding: 8px 12px;
  cursor: pointer;
  font-weight: 600;
}
.tag.active {
  border-color: rgba(79, 70, 229, 0.7);
  background: rgba(79, 70, 229, 0.12);
}
.tabs {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.tab {
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text);
  border-radius: 999px;
  padding: 8px 12px;
  cursor: pointer;
  font-weight: 800;
}
.tab.active {
  border-color: rgba(79, 70, 229, 0.7);
  background: rgba(79, 70, 229, 0.12);
}

.mode {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.mode-btn {
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text);
  border-radius: 999px;
  padding: 8px 12px;
  cursor: pointer;
  font-weight: 700;
}
.mode-btn.active {
  border-color: rgba(79, 70, 229, 0.7);
  background: rgba(79, 70, 229, 0.12);
}
.designer-wrap {
  border: 1px solid var(--color-border);
  border-radius: 12px;
  overflow: hidden;
  background: var(--color-background);
}
.designer-wrap.offscreen {
  position: absolute;
  left: -10000px;
  top: -10000px;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
}
.designer-iframe {
  width: 100%;
  height: 660px;
  border: 0;
  display: block;
}
.designer-hint {
  padding: 8px 10px;
  font-size: 12px;
  color: var(--color-text-muted);
  border-top: 1px solid var(--color-border);
}

.upload-wrap {
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-background);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.file {
  width: 100%;
}
.upload-hint {
  font-size: 12px;
  color: var(--color-text-muted);
}
.preview {
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: var(--color-background);
  overflow: hidden;
  min-height: 520px;
}
.preview iframe {
  width: 100%;
  height: 100%;
  min-height: 520px;
  border: 0;
}
.preview-empty {
  padding: 18px;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}
</style>

