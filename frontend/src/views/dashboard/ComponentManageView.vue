<script setup lang="ts">
import { computed, ref } from 'vue'
import { deleteComponent, generateComponentHtml, saveComponent, stripMarkdownCodeFence } from '@/api/component'
import { useComponentsStore } from '@/stores/components'
import { useAuthStore } from '@/stores/auth'

const store = useComponentsStore()
const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

const name = ref('')
const description = ref('')
const generating = ref(false)
const errorMsg = ref('')

const generatedHtml = ref('')
const previewTab = ref<'preview' | 'code'>('preview')

const items = computed(() => store.items)

const modalOpen = ref(false)
const modalTitle = ref('')
const modalHtml = ref('')

const canGenerate = computed(() => !!description.value.trim() && !generating.value)
const canSave = computed(() => !!name.value.trim() && !!generatedHtml.value.trim() && !generating.value)

async function onGenerate() {
  if (!description.value.trim()) {
    errorMsg.value = '请先填写组件描述'
    return
  }
  generating.value = true
  errorMsg.value = ''
  try {
    const html = await generateComponentHtml(description.value)
    generatedHtml.value = stripMarkdownCodeFence(html)
    previewTab.value = 'preview'
  } catch (e: unknown) {
    const ax = e as { response?: { data?: { message?: string } }; message?: string }
    errorMsg.value = ax.response?.data?.message || ax.message || '生成失败（请检查 llm-service 是否启动）'
  } finally {
    generating.value = false
  }
}

function onSave() {
  if (!canSave.value) {
    errorMsg.value = '请填写名称并先生成 HTML'
    return
  }
  if (!userId.value) {
    errorMsg.value = '请先登录'
    return
  }
  errorMsg.value = ''
  void (async () => {
    try {
      const saved = await saveComponent({
        userId: userId.value,
        name: name.value.trim(),
        description: description.value.trim(),
        html: generatedHtml.value.trim()
      })
      // 同时写入本地组件库（供 designer/index.html 读取）
      store.upsert({
        id: saved.id,
        name: saved.name,
        description: saved.description,
        html: generatedHtml.value.trim()
      })
      // 保留描述，便于继续迭代；清空名称/HTML 进入下一次生成
      name.value = ''
      generatedHtml.value = ''
    } catch (e: unknown) {
      const msg = (e as Error)?.message || '保存失败'
      errorMsg.value = msg
    }
  })()
}

function onPreviewItem(itemId: string) {
  const hit = store.items.find((x) => x.id === itemId)
  if (!hit) return
  modalTitle.value = hit.name
  modalHtml.value = hit.html
  modalOpen.value = true
}

function closeModal() {
  modalOpen.value = false
  modalTitle.value = ''
  modalHtml.value = ''
}

function onDelete(itemId: string) {
  const ok = window.confirm('确认删除该组件吗？（仅本地删除）')
  if (!ok) return
  store.remove(itemId)
  // 尝试同步删除服务端（若未登录/失败则忽略）
  if (userId.value) {
    void deleteComponent(userId.value, itemId).catch(() => {})
  }
}

function onClearAll() {
  const ok = window.confirm('确认清空所有组件吗？（仅本地清空）')
  if (!ok) return
  store.clearAll()
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="head">
        <div class="head-left">
          <h1 class="title">组件管理</h1>
        </div>
        <div class="head-right">
          <button type="button" class="btn-ghost" :disabled="store.count === 0" @click="onClearAll">清空组件库</button>
        </div>
      </div>

      <p v-if="errorMsg" class="msg error">{{ errorMsg }}</p>

      <div class="grid">
        <section class="left">
          <div class="field">
            <label>组件描述（给 LLM）</label>
            <textarea v-model="description" rows="7" placeholder="例如：一个用于展示关键结论的卡片，左上角有小标题，中央是大数字，右下角是说明文字；风格简约商务蓝。"></textarea>
          </div>

          <div class="field">
            <label>组件名称（保存用）</label>
            <input v-model="name" placeholder="例如：大数字结论卡片" />
          </div>

          <div class="actions">
            <button type="button" class="btn-primary" :disabled="!canGenerate" @click="onGenerate">
              {{ generating ? '生成中…' : '生成 HTML' }}
            </button>
            <button type="button" class="btn-primary" :disabled="!canSave" @click="onSave">添加到组件库</button>
          </div>

          <div class="field">
            <label>预览 / 代码</label>
            <div class="tabs">
              <button type="button" class="tab" :class="{ active: previewTab === 'preview' }" @click="previewTab = 'preview'">
                预览
              </button>
              <button type="button" class="tab" :class="{ active: previewTab === 'code' }" @click="previewTab = 'code'">
                HTML
              </button>
            </div>
          </div>

          <div class="preview">
            <template v-if="generatedHtml.trim()">
              <iframe
                v-if="previewTab === 'preview'"
                class="preview-iframe"
                :srcdoc="generatedHtml"
                title="component-preview"
                sandbox="allow-same-origin allow-scripts"
              />
              <textarea v-else class="code" :value="generatedHtml" readonly></textarea>
            </template>
            <div v-else class="empty">生成后会在此预览（建议组件根节点 `width:100%;height:100%`）。</div>
          </div>
        </section>

        <section class="right">
          <div class="library-head">
            <h2 class="sub-title">组件库</h2>
            <span class="count">{{ store.count }} 个</span>
          </div>

          <ul class="list">
            <li v-if="items.length === 0" class="empty-row">暂无组件</li>
            <li v-for="item in items" :key="item.id" class="row">
              <div class="row-main">
                <div class="row-title">{{ item.name }}</div>
                <div class="row-desc">{{ item.description }}</div>
              </div>
              <div class="row-actions">
                <button type="button" class="btn-small" @click="onPreviewItem(item.id)">预览</button>
                <button type="button" class="btn-small danger" @click="onDelete(item.id)">删除</button>
              </div>
            </li>
          </ul>
        </section>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="modalOpen" class="modal-mask" @click.self="closeModal">
          <div class="modal-box" role="dialog" aria-modal="true">
            <div class="modal-head">
              <span class="modal-title">{{ modalTitle || '组件预览' }}</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closeModal">×</button>
            </div>
            <div class="modal-body">
              <iframe
                v-if="modalHtml"
                class="modal-iframe"
                :srcdoc="modalHtml"
                title="component-modal-preview"
                sandbox="allow-same-origin allow-scripts"
              />
              <div v-else class="modal-empty">暂无预览内容</div>
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
  padding: 28px 24px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-background-soft);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.title {
  font-size: 1.4rem;
  font-weight: 800;
  color: var(--color-heading);
  margin: 0 0 6px;
}
.desc {
  color: var(--color-text-muted);
  margin: 0;
}
.btn-primary {
  padding: 10px 14px;
  border-radius: 10px;
  border: 0;
  font-weight: 800;
  cursor: pointer;
  color: #fff;
  background: var(--vt-c-indigo);
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-ghost {
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid var(--color-border);
  font-weight: 700;
  cursor: pointer;
  background: transparent;
  color: var(--color-text);
}
.btn-small {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid var(--color-border);
  cursor: pointer;
  background: transparent;
  color: var(--color-text);
  font-weight: 700;
}
.btn-small.danger {
  color: var(--vt-c-red);
  border-color: rgba(220, 38, 38, 0.35);
}
.msg.error {
  color: var(--vt-c-red);
  margin: 0;
}
.grid {
  flex: 1 1 0;
  min-height: 0;
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  gap: 16px;
}
@media (max-width: 980px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .actions {
    flex-wrap: wrap;
  }
  .actions .btn-primary {
    flex: 0 0 auto;
  }
}
.left,
.right {
  min-height: 0;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-background);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
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
.actions {
  display: flex;
  gap: 10px;
  flex-wrap: nowrap;
  align-items: center;
}
.actions .btn-primary {
  flex: 1 1 0;
}
.tabs {
  display: flex;
  gap: 10px;
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
.preview {
  flex: 1 1 0;
  min-height: 0;
  width: 100%;
  max-width: 100%;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
  height: clamp(360px, 46vh, 620px);
}
.preview-iframe {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}
.code {
  width: 100%;
  height: 100%;
  border: 0;
  padding: 12px;
  font-size: 12px;
  box-sizing: border-box;
  overflow: auto;
}
.empty {
  padding: 16px;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}
.library-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.sub-title {
  margin: 0;
  font-size: 1rem;
  font-weight: 800;
  color: var(--color-heading);
}
.count {
  color: var(--color-text-muted);
  font-size: 0.85rem;
}
.list {
  list-style: none;
  padding: 0;
  margin: 0;
  overflow: auto;
  flex: 1 1 0;
  min-height: 0;
  border-top: 1px solid var(--color-border);
}
.row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 12px 6px;
  border-bottom: 1px solid var(--color-border);
}
.row-title {
  font-weight: 800;
  color: var(--color-heading);
}
.row-desc {
  color: var(--color-text-muted);
  font-size: 0.9rem;
  margin-top: 2px;
}
.row-meta {
  color: var(--color-text-muted);
  font-size: 0.8rem;
  margin-top: 6px;
}
.row-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
}
.empty-row {
  padding: 14px 6px;
  color: var(--color-text-muted);
}

/* Modal */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 18px;
}
.modal-box {
  width: 96vw;
  max-width: 1400px;
  height: 92vh;
  max-height: 92vh;
  background: var(--color-background);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.modal-title {
  font-weight: 800;
  color: var(--color-heading);
}
.modal-close {
  width: 34px;
  height: 34px;
  border: none;
  background: var(--color-background-mute);
  border-radius: 10px;
  font-size: 1.4rem;
  line-height: 1;
  cursor: pointer;
  color: var(--color-text-muted);
}
.modal-close:hover {
  background: var(--color-border);
  color: var(--color-text);
}
.modal-body {
  flex: 1 1 0;
  min-height: 0;
  background: #fff;
}
.modal-iframe {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
  background: #fff;
}
.modal-empty {
  padding: 18px;
  color: var(--color-text-muted);
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
  transform: scale(0.98);
}
</style>
