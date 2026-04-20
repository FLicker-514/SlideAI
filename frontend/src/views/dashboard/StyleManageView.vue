<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import html2canvas from 'html2canvas'
import { useAuthStore } from '@/stores/auth'
import { listStyles, getStyleDetail, createStyleFromPpt, createStyleFromDescription, updateStyle, deleteStyle } from '@/api/style'
import type { StyleHistoryItem, StyleDetail } from '@/api/style'
import { getStyleAnalyzeResponse, getContentFromResponse, parseStyleAnalyzeContent, type StyleAnalyzeResult } from '@/api/llm'

const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

const styles = ref<StyleHistoryItem[]>([])
const loading = ref(false)

// 预览弹窗：仅一页（风格背景），不区分标题页/内容页/结尾页
const showPreview = ref(false)
const previewStyleId = ref('')
const previewStyleName = ref('')
const previewDetail = ref<StyleDetail | null>(null)
const previewLoading = ref(false)
const previewPage = ref<0 | 1>(0)

/** 预览用背景 HTML：后端已统一为单张，取 background1 即可 */
const previewBackgroundHtml = computed(() => {
  if (!previewDetail.value) return ''
  return previewDetail.value.background1 ?? previewDetail.value.background2 ?? previewDetail.value.background3 ?? ''
})

// 添加/生成新风格弹窗：两种方式 — 从 PPT 上传 / 通过描述生成
const showCreate = ref(false)
const createMode = ref<'upload' | 'description'>('upload')
const createName = ref('')
const createDescTags = ref<string[]>([])
const createUsageTags = ref<string[]>([])
const createDescInput = ref('')
const createUsageInput = ref('')
const createFile = ref<File | null>(null)
const createDescription = ref('')
const createLoading = ref(false)
const createError = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)
/** 上传并解析后得到的风格 ID，用于同弹窗内保存元数据，不切换弹窗 */
const createStyleIdAfterUpload = ref('')
const createFontHtml = ref('')
const createHeading3Font = ref('')
const createBodyFont = ref('')
const createSaveLoading = ref(false)

// 编辑标签弹窗（生成完成后 AI 分析 / 或从列表点击编辑）
const showEditMeta = ref(false)
const editMetaStyleId = ref('')
const editMetaName = ref('')
const editMetaDescTags = ref<string[]>([])
const editMetaUsageTags = ref<string[]>([])
const editMetaDescInput = ref('')
const editMetaUsageInput = ref('')
const editMetaLoading = ref(false)
const editMetaError = ref('')
const editMetaFromList = ref(false)
const editMetaFontHtml = ref('')
const editMetaHeading3Font = ref('')
const editMetaBodyFont = ref('')

function loadList() {
  if (!userId.value) {
    styles.value = []
    return
  }
  loading.value = true
  listStyles(userId.value)
    .then((res) => {
      styles.value = Array.isArray(res.data) ? res.data : []
    })
    .catch(() => { styles.value = [] })
    .finally(() => { loading.value = false })
}

function openPreview(item: StyleHistoryItem) {
  previewStyleId.value = item.id
  previewStyleName.value = item.name || '未命名'
  previewDetail.value = null
  previewPage.value = 0
  previewLoading.value = true
  showPreview.value = true
  getStyleDetail(userId.value, item.id)
    .then((res) => {
      if (res.code === 200 && res.data) previewDetail.value = res.data
    })
    .finally(() => { previewLoading.value = false })
}

function closePreview() {
  showPreview.value = false
  previewDetail.value = null
}

function prevPreviewPage() {
  previewPage.value = previewPage.value === 0 ? 1 : 0
}

function nextPreviewPage() {
  previewPage.value = previewPage.value === 1 ? 0 : 1
}

function wrapHtml(html: string): string {
  if (!html?.trim()) return ''
  const s = html.trim()
  if (/^<!doctype|^<html/i.test(s)) return s
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>${s}</body></html>`
}

/** 包装字体演示 HTML，注入浅灰背景以便白色字体可见 */
function wrapFontDemoHtml(html: string): string {
  if (!html?.trim()) return ''
  const s = html.trim()
  // 字体预览页：加大行高与段落间距，避免拥挤
  const style =
    '<style>' +
    'html,body{height:100%;}' +
    'body{background:#e8e8e8;min-height:100%;margin:0;padding:20px 24px;line-height:1.65;}' +
    'h1,h2,h3,p{margin:0;}' +
    'h1{margin-top:8px;margin-bottom:14px;font-size:40px;line-height:1.22;}' +
    'h2{margin-top:10px;margin-bottom:12px;font-size:30px;line-height:1.25;}' +
    'h3{margin-top:10px;margin-bottom:10px;font-size:22px;line-height:1.3;}' +
    'p{margin-top:8px;margin-bottom:0;font-size:16px;line-height:1.8;}' +
    '</style>'
  if (/^<!doctype|^<html/i.test(s)) {
    return s.replace(/<head(\s[^>]*)?>/i, (m) => m + style)
  }
  return `<!DOCTYPE html><html><head><meta charset="UTF-8">${style}</head><body>${s}</body></html>`
}

/** 标题页渲染转图的超时时间（毫秒） */
const TITLE_PAGE_RENDER_TIMEOUT = 60000
/** doc.write 后部分环境 onload 不触发，此时间后强制尝试截图 */
const TITLE_PAGE_FALLBACK_MS = 2500

const DEBUG_STYLE = true
function debugStyle(msg: string, ...args: unknown[]) {
  if (DEBUG_STYLE) console.log('[风格生成]', msg, ...args)
}

/** 将标题页 HTML 在 iframe 中渲染后转为 PNG 的 base64，供 API 使用 */
function htmlToImageBase64(html: string): Promise<{ base64: string; mediaType: string }> {
  return new Promise((resolve, reject) => {
    debugStyle('htmlToImageBase64 开始, HTML 长度:', html?.length ?? 0)
    const fullHtml = wrapHtml(html)
    const iframe = document.createElement('iframe')
    iframe.setAttribute('style', 'position:fixed;left:-9999px;top:0;width:960px;height:540px;border:none;')
    document.body.appendChild(iframe)
    const doc = iframe.contentDocument || iframe.contentWindow?.document
    if (!doc) {
      debugStyle('htmlToImageBase64 失败: 无法创建 iframe 文档')
      document.body.removeChild(iframe)
      return reject(new Error('无法创建 iframe 文档'))
    }
    let captureStarted = false
    const done = (err?: unknown) => {
      clearTimeout(t)
      if (fallbackT != null) clearTimeout(fallbackT)
      if (iframe.parentNode) document.body.removeChild(iframe)
      if (err) {
        debugStyle('htmlToImageBase64 done(错误):', err)
        reject(err)
      }
    }
    const t = setTimeout(() => {
      debugStyle('htmlToImageBase64 超时, captureStarted=', captureStarted)
      done(new Error('标题页渲染超时'))
    }, TITLE_PAGE_RENDER_TIMEOUT)
    let fallbackT: ReturnType<typeof setTimeout> | null = null
    doc.open()
    doc.write(fullHtml)
    doc.close()
    debugStyle('htmlToImageBase64 doc.write 完成, 等待 onload 或 fallback(', TITLE_PAGE_FALLBACK_MS, 'ms)')
    const runCapture = () => {
      if (captureStarted) {
        debugStyle('runCapture 跳过: 已开始过')
        return
      }
      captureStarted = true
      if (fallbackT != null) {
        clearTimeout(fallbackT)
        fallbackT = null
      }
      const body = doc.body
      if (!body) {
        debugStyle('runCapture 失败: iframe 无 body')
        done(new Error('iframe 无 body'))
        return
      }
      debugStyle('runCapture 开始 html2canvas')
      html2canvas(body, { useCORS: true, scale: 1, width: 960, height: 540 })
        .then((canvas) => {
          clearTimeout(t)
          const dataUrl = canvas.toDataURL('image/png')
          const base64 = dataUrl.replace(/^data:image\/png;base64,/, '')
          if (iframe.parentNode) document.body.removeChild(iframe)
          debugStyle('htmlToImageBase64 成功, base64 长度:', base64?.length ?? 0)
          resolve({ base64, mediaType: 'image/png' })
        })
        .catch((err) => {
          debugStyle('runCapture html2canvas 失败:', err)
          done(err)
          reject(err)
        })
    }
    iframe.onload = () => {
      debugStyle('htmlToImageBase64 iframe.onload 触发, 400ms 后 runCapture')
      requestAnimationFrame(() => {
        setTimeout(runCapture, 400)
      })
    }
    iframe.onerror = () => {
      debugStyle('htmlToImageBase64 iframe.onerror')
      done(new Error('iframe 加载失败'))
      reject(new Error('iframe 加载失败'))
    }
    fallbackT = setTimeout(() => {
      fallbackT = null
      debugStyle('htmlToImageBase64 fallback 触发, captureStarted=', captureStarted)
      if (!iframe.parentNode) return
      runCapture()
    }, TITLE_PAGE_FALLBACK_MS)
  })
}

function openCreateModal() {
  showCreate.value = true
  createMode.value = 'upload'
  createName.value = ''
  createDescTags.value = []
  createUsageTags.value = []
  createDescInput.value = ''
  createUsageInput.value = ''
  createFile.value = null
  createDescription.value = ''
  createError.value = ''
  createStyleIdAfterUpload.value = ''
  createFontHtml.value = ''
  createHeading3Font.value = ''
  createBodyFont.value = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function addCreateDescTag() {
  const v = createDescInput.value.trim()
  if (v && !createDescTags.value.includes(v)) {
    createDescTags.value = [...createDescTags.value, v]
    createDescInput.value = ''
  }
}

function addCreateUsageTag() {
  const v = createUsageInput.value.trim()
  if (v && !createUsageTags.value.includes(v)) {
    createUsageTags.value = [...createUsageTags.value, v]
    createUsageInput.value = ''
  }
}

function removeCreateDescTag(i: number) {
  createDescTags.value = createDescTags.value.filter((_, idx) => idx !== i)
}

function removeCreateUsageTag(i: number) {
  createUsageTags.value = createUsageTags.value.filter((_, idx) => idx !== i)
}

function onFileChange(e: Event) {
  const input = (e.target as HTMLInputElement).files?.[0]
  createFile.value = input && (input.name.endsWith('.ppt') || input.name.endsWith('.pptx')) ? input : null
  if (!createFile.value && input) createError.value = '请选择 .ppt 或 .pptx 文件'
  else createError.value = ''
}

function addEditDescTag() {
  const v = editMetaDescInput.value.trim()
  if (v && !editMetaDescTags.value.includes(v)) {
    editMetaDescTags.value = [...editMetaDescTags.value, v]
    editMetaDescInput.value = ''
  }
}

function addEditUsageTag() {
  const v = editMetaUsageInput.value.trim()
  if (v && !editMetaUsageTags.value.includes(v)) {
    editMetaUsageTags.value = [...editMetaUsageTags.value, v]
    editMetaUsageInput.value = ''
  }
}

function removeEditDescTag(i: number) {
  editMetaDescTags.value = editMetaDescTags.value.filter((_, idx) => idx !== i)
}

function removeEditUsageTag(i: number) {
  editMetaUsageTags.value = editMetaUsageTags.value.filter((_, idx) => idx !== i)
}

async function submitCreateByDescription() {
  const desc = createDescription.value.trim()
  if (!desc) {
    createError.value = '请填写风格描述'
    return
  }
  createError.value = ''
  createLoading.value = true
  try {
    const res = await createStyleFromDescription({
      userId: userId.value,
      description: desc,
      name: createName.value.trim() || undefined
    })
    if (res.code !== 200 || !res.data?.id) {
      createError.value = res.message || '生成失败'
      return
    }
    const data = res.data
    createStyleIdAfterUpload.value = data.id
    createName.value = data.name ?? ''
    createDescTags.value = Array.isArray(data.descriptionTags) ? [...data.descriptionTags] : []
    createUsageTags.value = Array.isArray(data.usageScenarioTags) ? [...data.usageScenarioTags] : []
    createHeading3Font.value = (data as { heading3Font?: string }).heading3Font ?? ''
    createBodyFont.value = (data as { bodyFont?: string }).bodyFont ?? ''
    createFontHtml.value = ''
    loadList()
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    createError.value = String(msg || '请求失败')
  } finally {
    createLoading.value = false
  }
}

async function submitCreate() {
  if (!createFile.value) {
    createError.value = '请选择 PPT 文件'
    return
  }
  createError.value = ''
  createLoading.value = true
  debugStyle('submitCreate 开始')
  try {
    debugStyle('submitCreate 调用 createStyleFromPpt (上传 PPT)...')
    const res = await createStyleFromPpt({
      userId: userId.value,
      file: createFile.value
    })
    debugStyle('submitCreate createStyleFromPpt 返回:', res.code, res.message, res.data?.id)
    if (res.code !== 200 || !res.data?.id) {
      createError.value = res.message || '生成失败'
      return
    }
    const styleId = res.data.id
    loadList()
    debugStyle('submitCreate 调用 getStyleDetail, styleId=', styleId)
    const detailRes = await getStyleDetail(userId.value, styleId)
    debugStyle('submitCreate getStyleDetail 返回:', detailRes.code, 'background1 长度=', detailRes.data?.background1?.length ?? 0)
    const userHint = `名称：${createName.value.trim() || '未填'}；风格：${createDescTags.value.join('，') || '未填'}；适用场景：${createUsageTags.value.join('，') || '未填'}`
    let suggested: StyleAnalyzeResult = {}
    if (detailRes.code === 200 && detailRes.data?.background1) {
      try {
        debugStyle('submitCreate 调用 htmlToImageBase64 (标题页转图)...')
        const { base64, mediaType } = await htmlToImageBase64(detailRes.data.background1)
        debugStyle('submitCreate htmlToImageBase64 完成, 调用 getStyleAnalyzeResponse (AI 分析)...')
        const rawRes = await getStyleAnalyzeResponse(`【用户参考】${userHint}`, {
          imageBase64: base64,
          imageMediaType: mediaType
        })
        debugStyle('submitCreate getStyleAnalyzeResponse 返回')
        const contentStr = getContentFromResponse(rawRes)
        debugStyle('submitCreate AI 返回 content 长度:', contentStr?.length ?? 0)
        suggested = parseStyleAnalyzeContent(contentStr)
        if (Object.keys(suggested).length === 0 && contentStr) {
          try {
            const direct = JSON.parse(contentStr) as StyleAnalyzeResult
            if (direct && typeof direct === 'object') suggested = direct
          } catch {
            /* 再试一次从 content 中截取 JSON */
            const start = contentStr.indexOf('{')
            const end = contentStr.lastIndexOf('}')
            if (start !== -1 && end > start) {
              const inner = JSON.parse(contentStr.slice(start, end + 1)) as StyleAnalyzeResult
              if (inner && typeof inner === 'object') suggested = inner
            }
          }
        }
        if (!contentStr && Object.keys(suggested).length === 0) {
          createError.value = 'AI 未返回有效内容，请检查 llm-service 或稍后重试'
        } else if (Object.keys(suggested).length === 0 && contentStr) {
          createError.value = 'AI 返回格式解析失败，请手动填写后保存'
        }
      } catch (e: unknown) {
        const msg = e && typeof e === 'object' && 'response' in e
          ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
          : (e as Error)?.message
        debugStyle('submitCreate AI 分析异常:', e)
        const isTimeout = typeof msg === 'string' && (msg.includes('timeout') || msg.includes('超时'))
        createError.value = isTimeout
          ? 'AI 分析超时，您可手动填写名称与标签后保存。'
          : `AI 分析请求失败: ${msg ?? '网络或服务异常'}，您可手动填写后保存。`
      }
    }
    // 立即将 AI 结果写入 history.json 与字体文件，并刷新列表
    const hasSuggested = suggested.styleName != null || (Array.isArray(suggested.descriptionTags) && suggested.descriptionTags.length > 0) ||
      (Array.isArray(suggested.usageScenarioTags) && suggested.usageScenarioTags.length > 0) ||
      suggested.fontHtml != null || suggested.heading3Font != null || suggested.bodyFont != null
    if (hasSuggested) {
      try {
        debugStyle('submitCreate 调用 updateStyle 保存 AI 建议...')
        const payload: Parameters<typeof updateStyle>[2] = {
          name: (suggested.styleName ?? createName.value.trim()) || undefined,
          descriptionTags: Array.isArray(suggested.descriptionTags) ? suggested.descriptionTags : [],
          usageScenarioTags: Array.isArray(suggested.usageScenarioTags) ? suggested.usageScenarioTags : []
        }
        if (suggested.fontHtml) payload.fontDemoHtml = suggested.fontHtml
        if (suggested.heading3Font) payload.heading3Font = suggested.heading3Font
        if (suggested.bodyFont) payload.bodyFont = suggested.bodyFont
        const updateRes = await updateStyle(userId.value, styleId, payload)
        debugStyle('submitCreate updateStyle 返回:', updateRes.code)
        if (updateRes.code === 200) {
          loadList()
        } else {
          createError.value = `自动保存元数据失败: ${updateRes.message ?? '未知错误'}`
        }
      } catch (e: unknown) {
        const msg = e && typeof e === 'object' && 'response' in e
          ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
          : (e as Error)?.message
        createError.value = `自动保存元数据失败: ${msg ?? '网络或服务异常'}`
      }
    }
    // 同弹窗内回填 AI 结果，不切换弹窗；用户可编辑后点「保存」
    createStyleIdAfterUpload.value = styleId
    createName.value = suggested.styleName ?? createName.value.trim() ?? ''
    createDescTags.value = Array.isArray(suggested.descriptionTags) ? [...suggested.descriptionTags] : [...createDescTags.value]
    createUsageTags.value = Array.isArray(suggested.usageScenarioTags) ? [...suggested.usageScenarioTags] : [...createUsageTags.value]
    createFontHtml.value = suggested.fontHtml ?? ''
    createHeading3Font.value = suggested.heading3Font ?? ''
    createBodyFont.value = suggested.bodyFont ?? ''
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    debugStyle('submitCreate 外层 catch:', e)
    createError.value = String(msg || '请求失败')
  } finally {
    debugStyle('submitCreate 结束, createLoading=false')
    createLoading.value = false
  }
}

function closeCreateModal() {
  showCreate.value = false
}

async function saveCreateMeta() {
  if (!createStyleIdAfterUpload.value) return
  createError.value = ''
  createSaveLoading.value = true
  try {
    const payload: Parameters<typeof updateStyle>[2] = {
      name: createName.value.trim() || undefined,
      descriptionTags: createDescTags.value,
      usageScenarioTags: createUsageTags.value
    }
    if (createFontHtml.value) payload.fontDemoHtml = createFontHtml.value
    if (createHeading3Font.value) payload.heading3Font = createHeading3Font.value
    if (createBodyFont.value) payload.bodyFont = createBodyFont.value
    const res = await updateStyle(userId.value, createStyleIdAfterUpload.value, payload)
    if (res.code === 200) {
      createStyleIdAfterUpload.value = ''
      loadList()
      closeCreateModal()
    } else {
      createError.value = res.message || '保存失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    createError.value = String(msg || '请求失败')
  } finally {
    createSaveLoading.value = false
  }
}

async function saveEditMeta() {
  editMetaError.value = ''
  editMetaLoading.value = true
  try {
    const payload: Parameters<typeof updateStyle>[2] = {
      name: editMetaName.value.trim() || undefined,
      descriptionTags: editMetaDescTags.value,
      usageScenarioTags: editMetaUsageTags.value
    }
    if (editMetaFontHtml.value) payload.fontDemoHtml = editMetaFontHtml.value
    if (editMetaHeading3Font.value) payload.heading3Font = editMetaHeading3Font.value
    if (editMetaBodyFont.value) payload.bodyFont = editMetaBodyFont.value
    const res = await updateStyle(userId.value, editMetaStyleId.value, payload)
    if (res.code === 200) {
      closeEditMeta()
      loadList()
    } else {
      editMetaError.value = res.message || '保存失败'
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    editMetaError.value = String(msg || '请求失败')
  } finally {
    editMetaLoading.value = false
  }
}

function openEditModal(item: StyleHistoryItem) {
  editMetaStyleId.value = item.id
  editMetaName.value = item.name ?? ''
  editMetaDescTags.value = [...(item.descriptionTags ?? [])]
  editMetaUsageTags.value = [...(item.usageScenarioTags ?? [])]
  editMetaFontHtml.value = ''
  editMetaHeading3Font.value = ''
  editMetaBodyFont.value = ''
  editMetaDescInput.value = ''
  editMetaUsageInput.value = ''
  editMetaError.value = ''
  editMetaFromList.value = true
  showEditMeta.value = true
}

function closeEditMeta() {
  showEditMeta.value = false
  loadList()
}

const deletingStyleId = ref<string | null>(null)
async function confirmDelete(item: StyleHistoryItem) {
  if (!confirm(`确定要删除风格「${item.name || '未命名'}」吗？将同时删除对应的背景与字体文件。`)) return
  deletingStyleId.value = item.id
  try {
    const res = await deleteStyle(userId.value, item.id)
    if (res.code === 200) {
      if (showPreview.value && previewStyleId.value === item.id) closePreview()
      loadList()
    } else {
      alert(res.message || '删除失败')
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    alert(String(msg || '删除失败'))
  } finally {
    deletingStyleId.value = null
  }
}

onMounted(loadList)
watch(userId, () => loadList(), { immediate: false })
</script>

<template>
  <div class="page">
    <div class="panel">
    <div class="style-page">
      <div class="page-head">
        <h1 class="card-title">风格管理</h1>
        <button type="button" class="btn-primary" :disabled="!userId" @click="openCreateModal">
          添加
        </button>
      </div>

      <div v-if="!userId" class="hint">请先登录</div>
    <div v-else-if="loading" class="hint">加载中…</div>
    <div v-else-if="styles.length === 0" class="hint">暂无风格</div>
    <div v-else class="style-table-wrap">
      <table class="style-table">
        <thead>
          <tr>
            <th class="col-name">名称</th>
            <th class="col-tags">风格</th>
            <th class="col-tags">适用场景</th>
            <th class="col-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in styles" :key="item.id" class="style-row">
            <td class="col-name">{{ item.name || '未命名' }}</td>
            <td class="col-tags">
              <span v-for="t in (item.descriptionTags ?? [])" :key="'d-' + t" class="tag">{{ t }}</span>
              <span v-if="!(item.descriptionTags ?? []).length" class="tag-muted">—</span>
            </td>
            <td class="col-tags">
              <span v-for="t in (item.usageScenarioTags ?? [])" :key="'u-' + t" class="tag">{{ t }}</span>
              <span v-if="!(item.usageScenarioTags ?? []).length" class="tag-muted">—</span>
            </td>
            <td class="col-actions">
              <button type="button" class="btn-link" @click="openPreview(item)">预览</button>
              <button type="button" class="btn-link" @click="openEditModal(item)">编辑</button>
              <button
                type="button"
                class="btn-link btn-link-danger"
                :disabled="deletingStyleId === item.id"
                @click="confirmDelete(item)"
              >
                {{ deletingStyleId === item.id ? '删除中…' : '删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
  </div>
  </div>

  <!-- 预览弹窗：单页展示风格背景，不区分标题页/内容页/结尾页 -->
  <Teleport to="body">
    <div v-if="showPreview" class="modal-mask" @click.self="closePreview">
      <div class="modal-box preview-modal">
        <div class="modal-head">
          <span class="modal-title">风格预览 — {{ previewStyleName }}</span>
          <button type="button" class="modal-close" aria-label="关闭" @click="closePreview">×</button>
        </div>
        <div class="modal-body">
          <div v-if="previewLoading" class="loading">加载中…</div>
          <template v-else-if="previewDetail">
            <div class="preview-carousel">
              <button type="button" class="carousel-arrow" aria-label="上一页" @click="prevPreviewPage">‹</button>
              <div class="preview-carousel-content">
                <div class="preview-label">
                  {{ previewPage === 0 ? '背景' : '字体展示' }}
                </div>
                <div class="preview-slot">
                  <template v-if="previewPage === 0">
                    <iframe
                      v-if="previewBackgroundHtml"
                      :srcdoc="wrapHtml(previewBackgroundHtml)"
                      class="preview-iframe"
                      title="风格背景"
                    />
                    <div v-else class="preview-empty">暂无背景</div>
                  </template>
                  <template v-else>
                    <div class="preview-font-page">
                      <iframe
                        v-if="previewDetail.fontDemoHtml"
                        :srcdoc="wrapFontDemoHtml(previewDetail.fontDemoHtml)"
                        class="preview-font-iframe"
                        title="字体展示"
                      />
                      <div v-else class="preview-font-empty">暂无字体展示（可在编辑中补充并保存）</div>
                    </div>
                  </template>
                </div>
              </div>
              <button type="button" class="carousel-arrow" aria-label="下一页" @click="nextPreviewPage">›</button>
            </div>
          </template>
        </div>
      </div>
    </div>
  </Teleport>

  <!-- 生成新风格弹窗 -->
  <Teleport to="body">
    <div v-if="showCreate" class="modal-mask" @click.self="closeCreateModal">
      <div class="modal-box create-modal">
        <div class="modal-head">
          <span class="modal-title">生成新的风格</span>
          <button type="button" class="modal-close" aria-label="关闭" @click="closeCreateModal">×</button>
        </div>
        <div class="modal-body">
          <!-- 生成成功后与「从 PPT 上传」一致：展示名称、风格标签、适用场景，可编辑后保存 -->
          <template v-if="createStyleIdAfterUpload">
            <p class="create-hint">生成成功，可编辑名称与标签后保存，或直接关闭。</p>
            <div class="form-row">
              <label>名称</label>
              <input v-model="createName" type="text" class="tag-input" style="width: 100%;" placeholder="风格名称" />
            </div>
            <div class="form-row">
              <label>风格标签</label>
              <div class="tag-wrap">
                <span v-for="(t, i) in createDescTags" :key="'d-' + i" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateDescTag(i)">×</button></span>
                <input v-model="createDescInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateDescTag()" />
              </div>
            </div>
            <div class="form-row">
              <label>适用场景</label>
              <div class="tag-wrap">
                <span v-for="(t, i) in createUsageTags" :key="'u-' + i" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateUsageTag(i)">×</button></span>
                <input v-model="createUsageInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateUsageTag()" />
              </div>
            </div>
          </template>

          <template v-else>
            <div class="create-mode-tabs">
              <button type="button" :class="['tab', { active: createMode === 'upload' }]" @click="createMode = 'upload'">从 PPT 上传</button>
              <button type="button" :class="['tab', { active: createMode === 'description' }]" @click="createMode = 'description'">通过描述生成</button>
            </div>

            <template v-if="createMode === 'upload'">
              <div class="form-row">
                <label>PPT 文件</label>
                <input ref="fileInputRef" type="file" accept=".ppt,.pptx" @change="onFileChange" />
                <span v-if="createFile" class="file-name">{{ createFile.name }}</span>
              </div>
              <div class="form-row">
                <label>名称</label>
                <input v-model="createName" type="text" class="tag-input" style="width: 100%;" placeholder="AI 将自动填写，可编辑" />
              </div>
              <div class="form-row">
                <label>风格</label>
                <div class="tag-wrap">
                  <span v-for="(t, i) in createDescTags" :key="t" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateDescTag(i)">×</button></span>
                  <input v-model="createDescInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateDescTag()" />
                </div>
              </div>
              <div class="form-row">
                <label>适用场景</label>
                <div class="tag-wrap">
                  <span v-for="(t, i) in createUsageTags" :key="t" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateUsageTag(i)">×</button></span>
                  <input v-model="createUsageInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateUsageTag()" />
                </div>
              </div>
            </template>

            <template v-else>
              <div class="form-row">
                <label>风格描述</label>
                <textarea v-model="createDescription" class="tag-input" rows="4" placeholder="例如：科技感蓝色渐变、简约商务灰白、自然清新绿色植物背景" style="width: 100%; resize: vertical;" />
              </div>
              <div class="form-row">
                <label>名称</label>
                <input v-model="createName" type="text" class="tag-input" style="width: 100%;" placeholder="AI 将自动填写，可编辑" />
              </div>
              <div class="form-row">
                <label>风格</label>
                <div class="tag-wrap">
                  <span v-for="(t, i) in createDescTags" :key="'desc-' + i" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateDescTag(i)">×</button></span>
                  <input v-model="createDescInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateDescTag()" />
                </div>
              </div>
              <div class="form-row">
                <label>适用场景</label>
                <div class="tag-wrap">
                  <span v-for="(t, i) in createUsageTags" :key="'use-' + i" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeCreateUsageTag(i)">×</button></span>
                  <input v-model="createUsageInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addCreateUsageTag()" />
                </div>
              </div>
            </template>
          </template>

          <p v-if="createError" class="error">{{ createError }}</p>
          <div class="modal-footer">
            <template v-if="createStyleIdAfterUpload">
              <button type="button" class="btn-primary" :disabled="createSaveLoading" @click="saveCreateMeta">
                {{ createSaveLoading ? '保存中…' : '保存' }}
              </button>
              <button type="button" class="btn-ghost" @click="closeCreateModal">关闭</button>
            </template>
            <template v-else-if="createMode === 'description'">
              <button type="button" class="btn-primary" :disabled="!createDescription.trim() || createLoading" @click="submitCreateByDescription">
                {{ createLoading ? '生成中…' : '生成' }}
              </button>
              <button type="button" class="btn-ghost" @click="closeCreateModal">取消</button>
            </template>
            <template v-else>
              <button type="button" class="btn-primary" :disabled="!createFile || createLoading" @click="submitCreate">
                {{ createLoading ? '生成中…' : '生成' }}
              </button>
              <button type="button" class="btn-ghost" @click="closeCreateModal">取消</button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </Teleport>

  <!-- 编辑标签弹窗（生成完成后 AI 分析结果） -->
  <Teleport to="body">
    <div v-if="showEditMeta" class="modal-mask" @click.self="closeEditMeta">
      <div class="modal-box create-modal edit-modal">
        <div class="modal-head">
          <span class="modal-title">编辑风格名称与标签</span>
          <button type="button" class="modal-close" aria-label="关闭" @click="closeEditMeta">×</button>
        </div>
        <div class="modal-body">
          <p v-if="!editMetaFromList" class="create-hint">根据内容页已由 AI 分析出建议名称与标签，您可修改后保存；点击「跳过」则保留为空。</p>
          <div class="edit-form-row">
            <div class="form-row">
              <label>风格名称</label>
              <input v-model="editMetaName" type="text" class="tag-input" placeholder="如：商务蓝" />
            </div>
            <div class="form-row">
              <label>风格标签</label>
              <div class="tag-wrap">
                <span v-for="(t, i) in editMetaDescTags" :key="t" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeEditDescTag(i)">×</button></span>
                <input v-model="editMetaDescInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addEditDescTag()" />
              </div>
            </div>
            <div class="form-row">
              <label>适用场景标签</label>
              <div class="tag-wrap">
                <span v-for="(t, i) in editMetaUsageTags" :key="t" class="tag tag-removable">{{ t }}<button type="button" class="tag-remove" @click="removeEditUsageTag(i)">×</button></span>
                <input v-model="editMetaUsageInput" type="text" class="tag-input" placeholder="输入后回车" @keydown.enter.prevent="addEditUsageTag()" />
              </div>
            </div>
          </div>
          <p v-if="editMetaError" class="error">{{ editMetaError }}</p>
          <div class="modal-footer">
            <button type="button" class="btn-primary" :disabled="editMetaLoading" @click="saveEditMeta">
              {{ editMetaLoading ? '保存中…' : '保存' }}
            </button>
            <button type="button" class="btn-ghost" @click="closeEditMeta">{{ editMetaFromList ? '取消' : '跳过' }}</button>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.page {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.panel {
  width: 100%;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 32px 28px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-background-soft);
  box-shadow: var(--shadow-card);
}
.style-page { display: flex; flex-direction: column; gap: 20px; }
.page-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.card-title { margin: 0; font-size: 1.4rem; font-weight: 800; color: var(--color-heading); }
.hint { color: var(--color-text-muted); padding: 24px; }
.style-table-wrap { overflow: auto; border: 1px solid var(--color-border); border-radius: var(--radius); }
.style-table { width: 100%; border-collapse: collapse; background: var(--color-background); }
.style-table th, .style-table td { padding: 12px 14px; text-align: left; border-bottom: 1px solid var(--color-border); }
.style-table thead th { font-weight: 600; color: var(--color-heading); background: var(--color-background-soft); }
.style-table tbody tr:hover { background: var(--color-background-mute); }
.col-name { min-width: 120px; }
.col-tags { min-width: 140px; }
.col-actions { white-space: nowrap; }
.style-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.btn-link-danger { color: var(--color-danger, #dc2626); }
.btn-link-danger:hover:not(:disabled) { text-decoration: underline; }
.tag { display: inline-block; padding: 2px 8px; border-radius: 4px; background: var(--color-background-mute); font-size: 0.85rem; margin: 1px 2px 1px 0; }
.tag-muted { color: var(--color-text-muted); font-size: 0.9rem; }
.btn-link { background: none; border: none; color: var(--primary); cursor: pointer; font-size: 0.9rem; }
.create-mode-tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.create-mode-tabs .tab { padding: 6px 14px; border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-background); cursor: pointer; font-size: 0.9rem; }
.create-mode-tabs .tab.active { background: var(--color-primary, #2563eb); color: #fff; border-color: var(--color-primary, #2563eb); }
.create-hint { color: var(--color-text-muted); font-size: 0.9rem; margin: 0 0 12px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-box { background: var(--color-background); border-radius: var(--radius); max-width: 90vw; max-height: 90vh; overflow: hidden; display: flex; flex-direction: column; }
.create-modal { width: 92vw; max-width: 680px; min-width: 480px; height: min(88vh, 560px); max-height: 88vh; }
.create-modal .modal-body { flex: 1; min-height: 0; overflow: auto; padding: 18px; }
.edit-modal { width: 92vw; max-width: 720px; max-height: 88vh; }
.edit-modal .modal-body { max-height: 78vh; min-height: 360px; }
.edit-form-row { display: flex; gap: 20px; align-items: flex-start; margin-bottom: 16px; }
.edit-form-row .form-row { flex: 1; min-width: 0; margin-bottom: 0; }
.edit-form-row .tag-input { width: 100%; }
.edit-form-row .tag-wrap { flex-wrap: wrap; }
.preview-modal { width: 96vw; max-width: none; height: 96vh; max-height: 96vh; }
.preview-modal .modal-body { flex: 1; min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.preview-single { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--color-border); }
.modal-title { font-weight: 600; font-size: 1.05rem; }
.modal-close { background: none; border: none; font-size: 1.4rem; cursor: pointer; color: var(--color-text-muted); }
.modal-body { padding: 18px; overflow: auto; }
.preview-carousel { flex: 1; min-height: 0; display: flex; align-items: stretch; gap: 12px; }
.carousel-arrow { flex-shrink: 0; width: 44px; border: 1px solid var(--color-border); border-radius: var(--radius); background: var(--color-background-soft); font-size: 1.8rem; cursor: pointer; color: var(--color-heading); display: flex; align-items: center; justify-content: center; }
.carousel-arrow:hover:not(:disabled) { background: var(--color-background-mute); }
.carousel-arrow:disabled { opacity: 0.4; cursor: not-allowed; }
.preview-carousel-content { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.preview-label { margin: 12px 0 4px; font-size: 0.9rem; font-weight: 600; color: var(--color-heading); flex-shrink: 0; }
.preview-carousel-content .preview-slot { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.preview-iframe { width: 100%; flex: 1; min-height: 0; min-width: 0; border: 1px solid var(--color-border); border-radius: 4px; background: #fff; }
.preview-empty { width: 100%; flex: 1; min-height: 0; border: 1px solid var(--color-border); border-radius: 4px; background: var(--color-background-mute); display: flex; align-items: center; justify-content: center; color: var(--color-text-muted); font-size: 0.9rem; }
.preview-font-page { width: 100%; flex: 1; min-height: 0; min-width: 0; border: 1px solid var(--color-border); border-radius: 4px; background: #fff; padding: 12px 16px; display: flex; flex-direction: column; overflow: hidden; }
.preview-font-iframe { width: 100%; flex: 1; min-height: 0; min-width: 0; border: none; border-radius: 4px; background: #fff; }
.preview-font-empty { width: 100%; flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center; color: var(--color-text-muted); font-size: 0.9rem; }
.loading { padding: 24px; text-align: center; color: var(--color-text-muted); }
.create-hint { margin: 0 0 16px; font-size: 0.9rem; color: var(--color-text-muted); }
.form-row { margin-bottom: 14px; }
.form-row label { display: block; margin-bottom: 6px; font-size: 0.9rem; font-weight: 600; }
.form-row input[type="file"] { font-size: 0.9rem; }
.file-name { margin-left: 8px; font-size: 0.9rem; color: var(--color-text-muted); }
.tag-wrap { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.tag-input { width: 140px; padding: 6px 8px; border: 1px solid var(--color-border); border-radius: 4px; font-size: 0.9rem; }
.tag-removable { padding-right: 4px; }
.tag-remove { margin-left: 2px; background: none; border: none; cursor: pointer; color: var(--color-text-muted); font-size: 1rem; }
.error { margin: 8px 0; color: var(--color-danger, #dc2626); font-size: 0.9rem; }
.modal-footer { margin-top: 16px; display: flex; gap: 10px; }
.btn-primary { padding: 8px 18px; background: var(--primary); color: #fff; border: none; border-radius: var(--radius); cursor: pointer; font-weight: 500; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-ghost { padding: 8px 18px; background: transparent; border: 1px solid var(--color-border); border-radius: var(--radius); cursor: pointer; }
</style>
