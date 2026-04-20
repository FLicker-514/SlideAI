<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useAuthStore } from '@/stores/auth'
import {
  listPdfs,
  listImages,
  getPdfContent,
  getImageFileBlob,
  getPdfImages,
  getPdfImageFileBlob,
  createPpt,
  listPpts,
  getPpt,
  updatePptProcess,
  updatePptUpload,
  updatePptOutline,
  updatePptPageContents,
  updatePptLayoutCodes,
  updatePptStyle,
  updatePptGeneratedPages,
  deletePpt,
  type PdfEntry,
  type ImageEntry,
  type PptListItem,
  type PptState
} from '@/api/document'
import { getContentFromResponse, generatePageContent, selectPageImages, generateFullPageThemes, assignLayoutsBySemantics, generatePageHtml } from '@/api/llm'
import { generateOutlineFromWorkshop } from '@/api/outline'
import { listKnowledgeBases, type KnowledgeBaseDto } from '@/api/rag'
import {
  judgeOutlineRetrieve,
  judgePageRetrieve,
  gatherRagSnippets,
  gatherWebSummary
} from '@/api/workshopRetrieval'
import { listStyles, getStyleDetail, type StyleDetail } from '@/api/style'
import { templateApi, type ExampleLayoutItem } from '@/api/template'
import html2canvas from 'html2canvas'

/** 大纲单页结构（与后端 ppt-outline 约定一致） */
interface OutlineSlide {
  part?: string
  title: string
  points: string[]
  content?: string
}

/** 将大纲原始内容转为 Markdown：若为约定 JSON 则按 part/title/points 转，否则原样视为 Markdown */
function outlineRawToMarkdown(raw: string): string {
  const trimmed = raw?.trim() ?? ''
  if (!trimmed) return ''
  let jsonStr = trimmed
  const codeBlock = trimmed.match(/^```(?:json)?\s*([\s\S]*?)```\s*$/m)
  if (codeBlock?.[1]) jsonStr = codeBlock[1].trim()
  try {
    const arr = JSON.parse(jsonStr) as unknown
    if (!Array.isArray(arr) || arr.length === 0) return trimmed
    const lines: string[] = []
    for (const item of arr) {
      const slide = item as Record<string, unknown>
      const part = slide.part as string | undefined
      const title = slide.title as string | undefined
      const points = Array.isArray(slide.points) ? (slide.points as string[]) : []
      if (part) lines.push(`## ${part}`)
      if (title) lines.push(`### ${title}`)
      for (const p of points) if (p != null && String(p).trim()) lines.push(`- ${String(p).trim()}`)
      const content = slide.content as string | undefined
      if (content && String(content).trim()) lines.push(String(content).trim(), '')
    }
    return lines.join('\n')
  } catch {
    return trimmed
  }
}

const STEPS = ['内容上传', '形成大纲', '形成每页内容', '选择版式', '选择风格', '逐页生成', '制作完成'] as const
type StepIndex = 0 | 1 | 2 | 3 | 4 | 5 | 6

const auth = useAuthStore()
const userId = computed(() => auth.user?.id ?? '')

// 风格列表来自风格管理（style-service），仅能选择已有风格

// 静态逐页内容（前端测试用）
const STATIC_PAGE_HTML = (n: number) =>
  `<div style="padding:24px;font-family:sans-serif;">
    <h2>第 ${n} 页</h2>
    <p>此处为静态预览内容，便于前端测试流程。</p>
  </div>`

// 以往制作记录（暂无后端，模拟）
const historyList = ref<Array<{ id: string; title: string; updatedAt: string }>>([])
/** 当前编辑的 PPT 创作 id（从服务端恢复或新建后写入） */
const currentPptId = ref<string | null>(null)
/** 未完成的 PPT 创作列表（用于「继续做」） */
const pptList = ref<PptListItem[]>([])
const pptListLoading = ref(false)
const showModal = ref(false)
const currentStep = ref<StepIndex>(0)
const stepSaveError = ref('')

// 制作记录预览（仅查看已生成逐页 HTML）
const previewModalOpen = ref(false)
const previewLoading = ref(false)
const previewError = ref('')
const previewTopic = ref('')
const previewPptId = ref<string | null>(null)
const previewPages = ref<Array<{ id: number; html: string }>>([])
const previewPageIndex = ref(0)

function wrapGeneratedPageHtmlForPreview(html: string): string {
  const trimmed = stripMarkdownCodeFence(html || '')
  if (!trimmed) return ''
  const full = /^<!doctype|^<html/i.test(trimmed)
    ? trimmed
    : `<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>${trimmed}</body></html>`

  // 给预览页注入“按 1920x1080 自适应缩放”的包装，避免被 iframe 容器裁切
  if (full.includes('__fit_page')) return full
  const fitStyle = `
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#fff;}
#__fit_root{position:relative;width:100%;height:100%;overflow:hidden;}
#__fit_page{position:relative;top:0;left:0;transform-origin:top left;}
`
  const fitScript =
    '<scr' +
    'ipt>(function(){' +
    'function wrap(){' +
    'var body=document.body; if(!body) return;' +
    'var root=document.getElementById("__fit_root");' +
    'var page=document.getElementById("__fit_page");' +
    'if(root&&page) return;' +
    'root=document.createElement("div"); root.id="__fit_root";' +
    'page=document.createElement("div"); page.id="__fit_page";' +
    'while(body.firstChild){ page.appendChild(body.firstChild); }' +
    'root.appendChild(page); body.appendChild(root);' +
    '}' +
    'function getContentSize(){' +
    'var page=document.getElementById("__fit_page"); if(!page) return {w:1920,h:1080};' +
    // 优先使用 workshop-content 的宽高（很多页是绝对定位 1920x1080）
    'var wc=document.getElementById("workshop-content");' +
    'if(wc){' +
    'var sw=wc.scrollWidth||wc.offsetWidth||0; var sh=wc.scrollHeight||wc.offsetHeight||0;' +
    'var st=window.getComputedStyle(wc);' +
    'var w=parseFloat(st.width)||sw; var h=parseFloat(st.height)||sh;' +
    // 有些页内容会溢出 workshop-content 固定高度（1080），这里额外用后代元素 bbox 探测真实边界
    'try{' +
    'var wr=wc.getBoundingClientRect();' +
    'var maxR=0,maxB=0;' +
    'var list=wc.querySelectorAll("*");' +
    'for(var i=0;i<list.length;i++){' +
    'var el=list[i];' +
    // 跳过不可见/无尺寸节点，减少噪声
    'if(!el || el.tagName==="SCRIPT" || el.tagName==="STYLE" || el.tagName==="META" || el.tagName==="LINK") continue;' +
    'var r=el.getBoundingClientRect();' +
    'if(!r || (!r.width && !r.height)) continue;' +
    'maxR=Math.max(maxR,(r.right-wr.left));' +
    'maxB=Math.max(maxB,(r.bottom-wr.top));' +
    '}' +
    'if(maxR>0) w=Math.max(w||0,maxR);' +
    'if(maxB>0) h=Math.max(h||0,maxB);' +
    '}catch(e){}' +
    'if(w>0&&h>0) return {w:w,h:h};' +
    '}' +
    // 退化：用页面自身滚动尺寸（适用于普通文档流）
    'var w2=Math.max(page.scrollWidth||0,page.offsetWidth||0,document.documentElement.scrollWidth||0);' +
    'var h2=Math.max(page.scrollHeight||0,page.offsetHeight||0,document.documentElement.scrollHeight||0);' +
    // 绝对定位元素可能不计入 scrollWidth/scrollHeight，额外用 bounding box 探测真实内容范围
    'try{' +
    'var pr=page.getBoundingClientRect();' +
    'var maxR=0,maxB=0;' +
    'var nodes=page.querySelectorAll ? page.querySelectorAll(\"*\") : (page.children||[]);' +
    'for(var i=0;i<nodes.length;i++){' +
    'var el=nodes[i];' +
    'if(!el || el===page) continue;' +
    'if(el.tagName===\"SCRIPT\"||el.tagName===\"STYLE\"||el.tagName===\"META\"||el.tagName===\"LINK\") continue;' +
    'var r=el.getBoundingClientRect();' +
    'if(!r || (!r.width && !r.height)) continue;' +
    'maxR=Math.max(maxR,(r.right-pr.left));' +
    'maxB=Math.max(maxB,(r.bottom-pr.top));' +
    '}' +
    'if(maxR>0) w2=Math.max(w2,maxR);' +
    'if(maxB>0) h2=Math.max(h2,maxB);' +
    '}catch(e){}' +
    'if(w2>0&&h2>0) return {w:w2,h:h2};' +
    'return {w:1920,h:1080};' +
    '}' +
    'function fit(){' +
    'var page=document.getElementById("__fit_page"); if(!page) return;' +
    'var rw=window.innerWidth||0; var rh=window.innerHeight||0;' +
    'if(!rw||!rh) return;' +
    'var s=getContentSize();' +
    'var cw=Math.max(1,Number(s.w)||1920); var ch=Math.max(1,Number(s.h)||1080);' +
    'page.style.width=cw+"px"; page.style.height=ch+"px";' +
    'var scale=Math.min(rw/cw,rh/ch);' +
    'page.style.transform="scale("+scale+")";' +
    '}' +
    'window.addEventListener("load",function(){wrap();fit();setTimeout(fit,80);setTimeout(fit,250);});' +
    'window.addEventListener("resize",fit);' +
    'document.addEventListener("DOMContentLoaded",function(){wrap();fit();});' +
    // 某些页包含异步加载/字体渲染，延迟再算一次
    'try{var ro=new ResizeObserver(function(){fit();}); ro.observe(document.documentElement);}catch(e){}' +
    '})()</scr' +
    'ipt>'

  if (/<head(\s[^>]*)?>/i.test(full)) {
    return full.replace(/<head(\s[^>]*)?>/i, (m) => m + `<style>${fitStyle}</style>`)
      .replace(/<\/body\s*>/i, (m) => `${fitScript}${m}`)
  }
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>${fitStyle}</style></head><body>${full}${fitScript}</body></html>`
}

const previewTotalPages = computed(() => previewPages.value.length)
const previewCurrentPageNumber = computed(() => previewPageIndex.value + 1)
const previewCurrentSrcdoc = computed(() => {
  const page = previewPages.value[previewPageIndex.value]
  if (!page) return ''
  return wrapGeneratedPageHtmlForPreview(page.html)
})

// Step 1: 内容上传（仅从文件管理选择：已解析 PDF + 图片）
const parsedPdfList = ref<PdfEntry[]>([])
const imageList = ref<ImageEntry[]>([])
const step1PdfLoading = ref(false)
const step1ImageLoading = ref(false)
const selectedPdfIds = ref<string[]>([])
const selectedImageIds = ref<string[]>([])
const step1PdfSelect = ref('')
const step1ImageSelect = ref('')
const step1Topic = ref('')
/** 制作工坊可选：用于按需 RAG 检索的知识库 id */
const selectedKbId = ref('')
const kbListForWorkshop = ref<KnowledgeBaseDto[]>([])
const kbListLoading = ref(false)

// Step 3: 选择版式（从版式管理为每页选版式，可智能分配）
const layoutList = ref<ExampleLayoutItem[]>([])
const pageLayoutCodes = ref<string[]>([])
const stepLayoutLoading = ref(false)
const stepLayoutError = ref('')

// Step 4: 选择风格（仅从风格管理中已有的列表选择）- 提前声明供 loadStep3Styles / watch 使用
const styleList = ref<Array<{ id: string; name: string }>>([])
const step3StyleLoading = ref(false)
const step3StyleError = ref('')
const selectedStyleId = ref('')
const stylePreviewDetail = ref<StyleDetail | null>(null)
const stylePreviewLoading = ref(false)
const STYLE_PAGE_SIZE = 3
const styleListPage = ref(0)

const styleListPageItems = computed(() => {
  const list = styleList.value
  const start = styleListPage.value * STYLE_PAGE_SIZE
  return list.slice(start, start + STYLE_PAGE_SIZE)
})
const styleListTotalPages = computed(() => Math.max(1, Math.ceil(styleList.value.length / STYLE_PAGE_SIZE)))

async function loadStep1Options() {
  if (!userId.value) return
  step1PdfLoading.value = true
  step1ImageLoading.value = true
  try {
    const [pdfRes, imgRes] = await Promise.all([listPdfs(userId.value), listImages(userId.value)])
    const pdfs = Array.isArray(pdfRes.data) ? pdfRes.data : []
    parsedPdfList.value = pdfs.filter((p) => p.parseStatus === 2)
    imageList.value = Array.isArray(imgRes.data) ? imgRes.data : []
  } catch {
    parsedPdfList.value = []
    imageList.value = []
  } finally {
    step1PdfLoading.value = false
    step1ImageLoading.value = false
  }
}

async function loadStep3Styles() {
  if (!userId.value) {
    styleList.value = []
    step3StyleError.value = '请先登录'
    return
  }
  step3StyleLoading.value = true
  step3StyleError.value = ''
  try {
    const res = await listStyles(userId.value)
    const list = Array.isArray(res.data) ? res.data : []
    styleList.value = list.map((s) => ({ id: s.id, name: s.name ?? s.id }))
    styleListPage.value = 0
    if (styleList.value.length > 0 && !styleList.value.some((s) => s.id === selectedStyleId.value)) {
      const firstStyle = styleList.value[0]
      if (firstStyle) selectedStyleId.value = firstStyle.id
    }
    if (styleList.value.length === 0) {
      selectedStyleId.value = ''
    }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    step3StyleError.value = err.response?.data?.message ?? err.message ?? '加载风格列表失败'
    styleList.value = []
    selectedStyleId.value = ''
  } finally {
    step3StyleLoading.value = false
  }
}

// 进入「选择版式」步时拉取版式列表并初始化每页版式
async function loadLayoutList() {
  const n = step3PageContents.value.length
  if (n > 0 && pageLayoutCodes.value.length !== n) {
    const prev = pageLayoutCodes.value
    pageLayoutCodes.value = Array.from({ length: n }, (_, i) => (prev[i] != null && prev[i] !== '' ? prev[i] : ''))
  }
  stepLayoutLoading.value = true
  stepLayoutError.value = ''
  try {
    const res = await templateApi.listLayouts()
    const body = res?.data as { code?: number; message?: string; data?: ExampleLayoutItem[] } | undefined
    layoutList.value = (body?.code === 200 && Array.isArray(body?.data)) ? body.data : []
    if (layoutList.value.length > 0) stepLayoutError.value = ''
    else if (body?.message) stepLayoutError.value = body.message
  } catch {
    layoutList.value = []
    stepLayoutError.value = '拉取版式列表失败，请确认 template-service 已启动'
  } finally {
    stepLayoutLoading.value = false
  }
}

// 根据语义智能分配版式
async function assignLayoutsClick() {
  if (layoutList.value.length === 0 || step3PageContents.value.length === 0) return
  stepLayoutLoading.value = true
  stepLayoutError.value = ''
  try {
    const codes = await assignLayoutsBySemantics(
      layoutList.value.map((l) => ({ code: l.code, name: l.name, description: l.description })),
      step3PageContents.value.map((p) => ({ theme: p.theme, textContent: p.textContent || '' }))
    )
    pageLayoutCodes.value = [...codes]
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    stepLayoutError.value = String(msg ?? '智能分配版式失败')
  } finally {
    stepLayoutLoading.value = false
  }
}

// 进入「选择风格」步时刷新风格列表
watch(currentStep, (step) => {
  if (step === 3) void loadLayoutList()
  if (step === 4) void loadStep3Styles()
})

/** 包装风格背景 HTML 供 iframe 使用，控制缩放比例（配合更大预览区域） */
const STYLE_PREVIEW_SCALE = 0.6
function wrapStylePreviewHtml(html: string): string {
  if (!html?.trim()) return ''
  const s = html.trim()
  const scaleStyle = `body{margin:0;transform:scale(${STYLE_PREVIEW_SCALE});transform-origin:top left;width:${100 / STYLE_PREVIEW_SCALE}%;min-height:${100 / STYLE_PREVIEW_SCALE}vh;}`
  if (/^<!doctype|^<html/i.test(s)) {
    return s.replace(/<head(\s[^>]*)?>/i, (m) => m + `<style>${scaleStyle}</style>`)
  }
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>${scaleStyle}</style></head><body>${s}</body></html>`
}

async function loadStylePreview() {
  const id = selectedStyleId.value
  const uid = userId.value
  if (!id || !uid) {
    stylePreviewDetail.value = null
    return
  }
  stylePreviewLoading.value = true
  stylePreviewDetail.value = null
  try {
    const res = await getStyleDetail(uid, id)
    if (res.code === 200 && res.data) stylePreviewDetail.value = res.data
  } finally {
    stylePreviewLoading.value = false
  }
}

// 每风格一张图，预览只显示这一张（兼容接口仍返回 background1/2/3 相同值）
const stylePreviewCurrentHtml = computed(() => {
  const d = stylePreviewDetail.value
  if (!d) return ''
  const html = d.background1 ?? d.background2 ?? d.background3 ?? ''
  return wrapStylePreviewHtml(html)
})

// 选中风格时加载预览
watch(selectedStyleId, (id) => {
  if (id && userId.value) void loadStylePreview()
  else stylePreviewDetail.value = null
})

function addSelectedPdf() {
  const id = step1PdfSelect.value
  if (!id || selectedPdfIds.value.includes(id)) return
  selectedPdfIds.value = [...selectedPdfIds.value, id]
}

function removePdf(fileId: string) {
  selectedPdfIds.value = selectedPdfIds.value.filter((id) => id !== fileId)
}

function addSelectedImage() {
  const id = step1ImageSelect.value
  if (!id || selectedImageIds.value.includes(id)) return
  selectedImageIds.value = [...selectedImageIds.value, id]
}

function removeImage(imageId: string) {
  selectedImageIds.value = selectedImageIds.value.filter((id) => id !== imageId)
}

function pdfLabel(fileId: string) {
  return parsedPdfList.value.find((p) => p.fileId === fileId)?.pdfFileName || fileId
}

/** 配图候选 id -> 展示用标签（含参考文档图与上传图），在形成每页内容时写入 */
const step3CandidateImageLabels = ref<Record<string, string>>({})
/** 配图候选 id 列表（用于手动添加图片时的下拉选项） */
const step3CandidateImageIds = ref<string[]>([])

function imageLabel(imageId: string) {
  return step3CandidateImageLabels.value[imageId]
    ?? imageList.value.find((i) => i.imageId === imageId)?.fileName
    ?? (imageId.startsWith('pdf|') ? imageId.split('|').pop() ?? imageId : imageId)
}

// Step 2: 形成大纲
const step2Outline = ref('')
const step2Loading = ref(false)
const step2Error = ref('')
/** 大纲区域显示模式：预览 / 编辑，默认先显示预览 */
const step2OutlineMode = ref<'preview' | 'edit'>('preview')
/** 用户曾到达过的最大步骤（用于回退后判断是否做了修改、是否舍弃后续步骤） */
const maxStepReached = ref(0)
/** 从更高步回退到某步时，该步当时的快照；若离开该步时与快照不同则视为「做了修改」，舍弃后续步骤 */
const returnSnapshot = ref<{ stepIndex: number; data: unknown } | null>(null)
/** 形成大纲时使用的参考文档内容，供下一步「形成每页内容」使用 */
const workshopDocumentContents = ref<string[]>([])

// Step 3: 形成每页内容（AI 按页生成文字 + 选 0～3 张图，暂不排版）
interface PageContentItem {
  theme: string
  textContent: string
  imageIds: string[]
  pageType?: string
}
const step3PageContents = ref<PageContentItem[]>([])
const step3Loading = ref(false)
const step3Error = ref('')
/** 单页重新生成时的页码（生成中时禁止下一步） */
const step3PageRegeneratingIndex = ref<number | null>(null)
/** 每页配图预览 blob URL 缓存（支持用户上传图 imageId 与参考文档图 pdf|fileId|imageId） */
const pageImageUrlCache = ref<Record<string, string>>({})

/** 为 step3 每页选中的图片加载 blob 并写入 pageImageUrlCache，供缩略图显示 */
function loadPageImageBlobs() {
  const uid = userId.value
  if (!uid) return
  const pages = step3PageContents.value
  const ids = new Set<string>()
  pages.forEach((p) => p.imageIds.forEach((id) => ids.add(id)))
  ids.forEach((imageId) => {
    if (pageImageUrlCache.value[imageId]) return
    if (imageId.startsWith('pdf|')) {
      const parts = imageId.split('|')
      if (parts.length >= 3) {
        const fileId = parts[1]
        if (!fileId) return
        const pdfImageId = parts.slice(2).join('|')
        getPdfImageFileBlob(uid, fileId, pdfImageId)
          .then((blob) => {
            if (!blob) return
            const url = URL.createObjectURL(blob)
            pageImageUrlCache.value = { ...pageImageUrlCache.value, [imageId]: url }
          })
          .catch((err) => {
            console.warn('PDF 图片加载失败', imageId, err)
          })
      }
      return
    }
    getImageFileBlob(uid, imageId)
      .then((blob) => {
        if (!blob) return
        const url = URL.createObjectURL(blob)
        pageImageUrlCache.value = { ...pageImageUrlCache.value, [imageId]: url }
      })
      .catch((err) => {
        console.warn('图片加载失败', imageId, err)
      })
  })
}

watch(
  [step3PageContents, userId],
  () => loadPageImageBlobs(),
  { immediate: true }
)

watch(currentStep, (step) => {
  if (step === 2 && step3PageContents.value.length > 0 && userId.value) {
    loadPageImageBlobs()
  }
})

/** 从大纲 JSON 解析出每页（每条为一页），返回 theme = title，可选 part */
function parseOutlineToSlides(raw: string): OutlineSlide[] {
  const trimmed = raw?.trim() ?? ''
  if (!trimmed) return []
  let jsonStr = trimmed
  const codeBlock = trimmed.match(/^```(?:json)?\s*([\s\S]*?)```\s*$/m)
  if (codeBlock?.[1]) jsonStr = codeBlock[1].trim()
  try {
    const arr = JSON.parse(jsonStr) as unknown
    if (!Array.isArray(arr)) return []
    return arr.map((item) => {
      const slide = item as Record<string, unknown>
      return {
        part: slide.part as string | undefined,
        title: String(slide.title ?? ''),
        points: Array.isArray(slide.points) ? (slide.points as string[]) : []
      }
    }).filter((s) => s.title.trim())
  } catch {
    return []
  }
}
/** 大纲预览：转为 Markdown 再渲染为安全 HTML */
const step2OutlineRenderedHtml = computed(() => {
  const md = outlineRawToMarkdown(step2Outline.value)
  if (!md) return ''
  const html = marked(md, { gfm: true }) as string
  return DOMPurify.sanitize(html, { ALLOWED_TAGS: ['p', 'br', 'h1', 'h2', 'h3', 'h4', 'ul', 'ol', 'li', 'strong', 'em', 'code', 'pre', 'blockquote'] })
})

// Step 4: 逐页生成（风格背景 + 每页文字与图片 → HTML 预览）
const step4Pages = ref<Array<{ id: number; html: string; status: 'pending' | 'generating' | 'done' }>>([])
const step4Loading = ref(false)
const step4Error = ref('')
/** 进入逐页生成时拉取的风格详情，供重新生成单页使用 */
const step4StyleDetail = ref<StyleDetail | null>(null)
/** Step4 当前预览页索引（一次只显示一页） */
const step4CurrentPageIndex = ref(0)

/** 逐页重新生成：弹窗（直接重生成 / 修改意见 + 上一版） */
const step4RegenerateModalOpen = ref(false)
const step4RegeneratePageIdx = ref(0)
const step4RegenerateMode = ref<'direct' | 'revision'>('direct')
const step4RegenerateFeedback = ref('')
const step4RegenerateInlineError = ref('')

function openStep4RegenerateModal(pageIndex: number) {
  step4RegeneratePageIdx.value = pageIndex
  step4RegenerateMode.value = 'direct'
  step4RegenerateFeedback.value = ''
  step4RegenerateInlineError.value = ''
  step4RegenerateModalOpen.value = true
}

function closeStep4RegenerateModal() {
  step4RegenerateModalOpen.value = false
}

async function confirmStep4Regenerate() {
  step4RegenerateInlineError.value = ''
  if (step4RegenerateMode.value === 'revision') {
    const t = step4RegenerateFeedback.value.trim()
    if (!t) {
      step4RegenerateInlineError.value = '请填写修改意见'
      return
    }
  }
  const idx = step4RegeneratePageIdx.value
  const mode = step4RegenerateMode.value
  const note = step4RegenerateFeedback.value.trim()
  closeStep4RegenerateModal()
  if (mode === 'revision') {
    await regenerateStep4Page(idx, { revisionNote: note })
  } else {
    await regenerateStep4Page(idx)
  }
}

/** 将 blob URL 转为 data URL，以便写入 iframe srcdoc 后图片能正常显示 */
function blobUrlToDataUrl(blobUrl: string): Promise<string> {
  return fetch(blobUrl)
    .then((r) => r.blob())
    .then(
      (blob) =>
        new Promise<string>((resolve, reject) => {
          const reader = new FileReader()
          reader.onload = () => resolve(reader.result as string)
          reader.onerror = () => reject(reader.error)
          reader.readAsDataURL(blob)
        })
    )
}

/** 从 blob URL 获取图片宽高（用于传给 AI 排版） */
function getImageDimensions(blobUrl: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve({ width: img.naturalWidth || 0, height: img.naturalHeight || 0 })
    img.onerror = () => reject(new Error('Image load failed'))
    img.src = blobUrl
  })
}

/** 去掉 AI 可能返回的 Markdown 代码块包裹（如 ```html ... ```），避免预览里显示原文 */
function stripMarkdownCodeFence(s: string): string {
  let t = (s || '').trim()
  // 统一换行为 \n，避免 \r\n 等导致正则不匹配
  t = t.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  // 去掉首行：整行仅为 ``` 或 ```html / ``` html 等（允许空格、任意语言标识）
  t = t.replace(/^\s*`{3}\s*[a-zA-Z0-9]*\s*[\r\n]+/i, '')
  // 去掉结尾：最后一个 ``` 及其前导换行
  t = t.replace(/[\r\n]+\s*`{3}\s*$/i, '')
  return t.trim()
}

/** 提示词中要求 AI 在根元素写入的背景图占位符，前端替换为实际 data URL */
const BACKGROUND_IMAGE_URL_PLACEHOLDER = '__BACKGROUND_IMAGE_URL__'

/** 将 AI 返回的内容层 HTML 片段注入风格背景，并为 data-index 的 img 填入 dataUrl；内容层用绝对定位叠在风格同一屏区域（不排在页面下方） */
function injectContentFragmentIntoStyleHtml(
  backgroundHtml: string,
  contentFragment: string,
  imageDataUrlsInOrder: string[]
): string {
  let fragment = stripMarkdownCodeFence(contentFragment)
  // 兜底：若仍以反引号开头（首行代码块标记），去掉该行
  if (fragment.startsWith('`')) {
    const lineEnd = fragment.indexOf('\n')
    fragment = lineEnd > 0 ? fragment.slice(lineEnd + 1) : fragment.replace(/^`+\s*[a-zA-Z0-9]*\s*/, '')
  }
  if (fragment.endsWith('`')) {
    fragment = fragment.replace(/\n?\s*`+\s*$/, '')
  }
  fragment = fragment.trim()
  fragment = fragment.replace(
    /<img\s([^>]*?)data-index="(\d+)"([^>]*?)>/gi,
    (_m, before, idxStr, after) => {
      const idx = parseInt(idxStr, 10)
      const src = imageDataUrlsInOrder[idx] ?? ''
      const safeSrc = src.replace(/"/g, '&quot;')
      return `<img ${before} data-index="${idxStr}" ${after} src="${safeSrc}">`
    }
  )
  // 仅保留带 background-image 的内容层，不再叠加原始背景 HTML。
  // 在 iframe 内根据可用宽高自适应缩放，并从左上角开始展示整页 1920x1080 内容。
  const contentOverlay = `
<div id="workshop-root" style="position:relative;width:100%;height:100%;overflow:hidden;">
  <div id="workshop-content" style="position:absolute;top:0;left:0;transform-origin:top left;width:1920px;height:1080px;box-sizing:border-box;">
    ${fragment}
  </div>
</div>`
  const baseStyle =
    'html,body{margin:0;padding:0;width:100%;height:100%;box-sizing:border-box;}' +
    'body{font-family:sans-serif;background:#fff;overflow:hidden;}';
  const script =
    '<scr' +
    'ipt>(function(){' +
    'function fit(){' +
    "var root=document.getElementById('workshop-root');" +
    "var content=document.getElementById('workshop-content');" +
    'if(!root||!content)return;' +
    'var rw=root.clientWidth||window.innerWidth||0;' +
    'var rh=root.clientHeight||window.innerHeight||0;' +
    'if(!rw||!rh)return;' +
    'var scale=Math.min(rw/1920,rh/1080);' +
    "content.style.transform='scale(' + scale + ')';" +
    '}' +
    'window.addEventListener("load",fit);' +
    'window.addEventListener("resize",fit);' +
    'setTimeout(fit,50);' +
    '})()</scr' +
    'ipt>'
  return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>${baseStyle}</style></head><body>${contentOverlay}${script}</body></html>`
}

/** 将风格背景 HTML 注入本页内容（标题 + 正文 + 图片），返回完整 HTML（AI 失败时兜底） */
function injectContentIntoStyleHtml(
  backgroundHtml: string,
  theme: string,
  textContent: string,
  imageDataUrls: string[]
): string {
  const escapeHtml = (s: string) =>
    s
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
  const themeSafe = escapeHtml(theme)
  const textSafe = escapeHtml(textContent).replace(/\n/g, '<br>\n')
  const imagesHtml =
    imageDataUrls.length > 0
      ? `<div class="workshop-images" style="display:flex;flex-wrap:wrap;gap:12px;margin-top:12px;">${imageDataUrls
          .map(
            (src) =>
              `<img src="${src.replace(/"/g, '&quot;')}" alt="" style="max-width:280px;max-height:180px;object-fit:contain;border-radius:4px;" />`
          )
          .join('')}</div>`
      : ''
  const contentBlock = `
<div id="workshop-content" style="position:absolute;inset:0;padding:48px 56px;overflow:auto;z-index:2;display:flex;flex-direction:column;gap:12px;">
  <h2 class="workshop-theme" style="margin:0;font-size:28px;font-weight:600;color:#1a1a1a;">${themeSafe}</h2>
  <div class="workshop-text" style="font-size:18px;line-height:1.6;color:#333;flex:1;">${textSafe}</div>
  ${imagesHtml}
</div>`
  const scaleStyle = `body{margin:0;transform:scale(${STYLE_PREVIEW_SCALE});transform-origin:top left;width:${100 / STYLE_PREVIEW_SCALE}%;min-height:${100 / STYLE_PREVIEW_SCALE}vh;}`
  const trimmed = (backgroundHtml || '').trim()
  if (!trimmed) {
    return `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{margin:0;padding:48px;font-family:sans-serif;}${scaleStyle}</style></head><body>${contentBlock}</body></html>`
  }
  let out = trimmed
  if (/<\/body\s*>/i.test(out)) {
    out = out.replace(/<\/body\s*>/i, contentBlock + '\n</body>')
  } else {
    out = out + contentBlock
  }
  if (/<head(\s[^>]*)?>/i.test(out)) {
    out = out.replace(/<head(\s[^>]*)?>/i, (m) => m + `<style>${scaleStyle}</style>`)
  } else {
    out = `<!DOCTYPE html><html><head><meta charset="UTF-8"><style>${scaleStyle}</style></head><body>${out}</body></html>`
  }
  return out
}

/** 将风格背景 HTML 在 iframe 中渲染后转为 PNG base64（仅 raw base64 字符串），无配图时发给 LLM */
function styleBackgroundHtmlToBase64(html: string): Promise<{ base64: string; mediaType: string }> {
  return new Promise((resolve, reject) => {
    const trimmed = html?.trim() ?? ''
    if (!trimmed) {
      reject(new Error('风格背景 HTML 为空'))
      return
    }
    const fullHtml = /^<!doctype|^<html/i.test(trimmed)
      ? trimmed
      : `<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body>${trimmed}</body></html>`
    const iframe = document.createElement('iframe')
    iframe.setAttribute('style', 'position:fixed;left:-9999px;top:0;width:960px;height:540px;border:none;')
    document.body.appendChild(iframe)
    const doc = iframe.contentDocument || iframe.contentWindow?.document
    if (!doc) {
      document.body.removeChild(iframe)
      reject(new Error('无法创建 iframe 文档'))
      return
    }
    const cleanup = () => {
      if (iframe.parentNode) document.body.removeChild(iframe)
    }
    doc.open()
    doc.write(fullHtml)
    doc.close()
    let captured = false
    const runCapture = () => {
      if (captured) return
      captured = true
      const body = doc.body
      if (!body) {
        cleanup()
        reject(new Error('iframe 无 body'))
        return
      }
      html2canvas(body, { useCORS: true, scale: 1, width: 960, height: 540 })
        .then((canvas) => {
          const dataUrl = canvas.toDataURL('image/png')
          const base64 = dataUrl.replace(/^data:image\/png;base64,/, '')
          cleanup()
          resolve({ base64, mediaType: 'image/png' })
        })
        .catch((err) => {
          cleanup()
          reject(err)
        })
    }
    iframe.onload = () => setTimeout(runCapture, 400)
    setTimeout(() => {
      if (!captured) runCapture()
    }, 3000)
  })
}

/** 逐页生成：每页单独请求 API，传入当页文字、图片描述与尺寸、版式代码、风格背景，AI 生成 HTML 后注入图片并叠在风格上 */
async function generateStep4Pages() {
  const pages = step3PageContents.value
  const styleId = selectedStyleId.value
  const uid = userId.value
  if (!pages.length || !styleId || !uid) {
    step4Error.value = '请先完成「形成每页内容」并选择风格'
    return
  }
  step4Loading.value = true
  step4Error.value = ''
  step4StyleDetail.value = null
  step4Pages.value = pages.map((_, i) => ({ id: i + 1, html: '', status: 'generating' as const }))
  try {
    const styleRes = await getStyleDetail(uid, styleId)
    if (styleRes.code !== 200 || !styleRes.data) {
      step4Error.value = '获取风格详情失败，请重试'
      step4Pages.value = []
      return
    }
    const style = styleRes.data
    step4StyleDetail.value = style
    // 每风格一张图，所有页共用同一背景
    const background = style.background1 ?? style.background2 ?? style.background3 ?? ''
    const allImageIds = new Set<string>()
    pages.forEach((p) => p.imageIds.forEach((id) => allImageIds.add(id)))
    const dataUrlMap: Record<string, string> = {}
    const sizeMap: Record<string, { width: number; height: number }> = {}
    await Promise.all(
      Array.from(allImageIds).map(async (imageId) => {
        const blobUrl = pageImageUrlCache.value[imageId]
        if (!blobUrl) return
        try {
          const [dataUrl, size] = await Promise.all([
            blobUrlToDataUrl(blobUrl),
            getImageDimensions(blobUrl).catch(() => ({ width: 400, height: 300 }))
          ])
          dataUrlMap[imageId] = dataUrl
          sizeMap[imageId] = size
        } catch {
          // 忽略单张失败
        }
      })
    )
    const candidates = await buildCandidateImages()
    const descMap: Record<string, string> = Object.fromEntries(
      candidates.map((c) => [c.id, c.description || ''])
    )
    for (let i = 0; i < pages.length; i++) {
      const page = pages[i]
      if (!page) continue
      let layoutHtml = ''
      const layoutCode = pageLayoutCodes.value[i] || layoutList.value[0]?.code || ''
      if (layoutCode) {
        try {
          const raw = await templateApi.getPreviewHtml(layoutCode)
          layoutHtml = typeof raw === 'string' ? raw : (raw as { data?: string })?.data ?? ''
        } catch {
          layoutHtml = ''
        }
      }
      const imagesForPage = (page.imageIds || []).map((id) => {
        const size = sizeMap[id] ?? { width: 400, height: 300 }
        return {
          description: descMap[id] ?? '',
          width: size.width,
          height: size.height
        }
      })
      const noImages = imagesForPage.length === 0
      let backgroundBase64: { base64: string; mediaType: string } | null = null
      if (background) {
        try {
          backgroundBase64 = await styleBackgroundHtmlToBase64(background)
        } catch {
          backgroundBase64 = null
        }
      }
      const contentImageBase64List: string[] = (page.imageIds || []).flatMap((id) => {
        const dataUrl = dataUrlMap[id]
        if (!dataUrl) return []
        const m = dataUrl.match(/^data:image\/[^;]+;base64,(.+)$/)
        const base64 = m ? m[1] : dataUrl.replace(/^data:image\/[^;]+;base64,/, '')
        return base64 ? [base64] : []
      })
      let html: string
      try {
        const contentFragment = await generatePageHtml({
          theme: page.theme,
          textContent: page.textContent || '',
          images: imagesForPage,
          layoutHtml,
          styleBackgroundHtml: background,
          ...(backgroundBase64
            ? {
                backgroundImageBase64: backgroundBase64.base64,
                backgroundImageMediaType: backgroundBase64.mediaType
              }
            : {}),
          ...(imagesForPage.length > 0 && contentImageBase64List.length > 0
            ? { contentImageBase64List }
            : {})
        })
        let trimmed = stripMarkdownCodeFence(contentFragment || '')
        const backgroundImageDataUrl = backgroundBase64
          ? `data:${backgroundBase64.mediaType};base64,${backgroundBase64.base64}`
          : ''
        trimmed = trimmed.split(BACKGROUND_IMAGE_URL_PLACEHOLDER).join(backgroundImageDataUrl)
        if (trimmed) {
          const imageDataUrls = (page.imageIds || []).map((id) => dataUrlMap[id] ?? '')
          html = injectContentFragmentIntoStyleHtml(background, trimmed, imageDataUrls)
        } else {
          const imageDataUrls = (page.imageIds || [])
            .map((id) => dataUrlMap[id])
            .filter((dataUrl): dataUrl is string => !!dataUrl)
          html = injectContentIntoStyleHtml(
            background,
            page.theme,
            page.textContent || '',
            imageDataUrls
          )
        }
      } catch (e: unknown) {
        const imageDataUrls = (page.imageIds || [])
          .map((id) => dataUrlMap[id])
          .filter((dataUrl): dataUrl is string => !!dataUrl)
        html = injectContentIntoStyleHtml(
          background,
          page.theme,
          page.textContent || '',
          imageDataUrls
        )
      }
      step4Pages.value = step4Pages.value.map((p, idx) =>
        idx === i ? { ...p, html, status: 'done' as const } : p
      )
      // 每页请求间隔，避免 Qwen 限流（Too many requests）
      if (i < pages.length - 1) {
        await new Promise((r) => setTimeout(r, 2200))
      }
    }
    if (currentPptId.value && userId.value) {
      try {
        await updatePptGeneratedPages(userId.value, currentPptId.value, {
          pages: step4Pages.value.map((p) => ({ id: p.id, html: p.html }))
        })
      } catch {
        // 保存失败不影响预览
      }
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    step4Error.value = String(msg ?? '逐页生成失败')
    step4Pages.value = []
  } finally {
    step4Loading.value = false
  }
}

/** 重新生成某一页的 HTML：单页请求 API，传入当页文字、图片描述与尺寸、版式、风格背景 */
async function regenerateStep4Page(
  pageIndex: number,
  opts?: { revisionNote?: string }
) {
  const pages = step3PageContents.value
  const page = pages[pageIndex]
  let style = step4StyleDetail.value
  if (!page) return
  if (!style && selectedStyleId.value && userId.value) {
    try {
      const res = await getStyleDetail(userId.value, selectedStyleId.value)
      if (res.code === 200 && res.data) {
        style = res.data
        step4StyleDetail.value = style
      }
    } catch {
      //
    }
  }
  if (!style) return
  step4Pages.value = step4Pages.value.map((p, idx) =>
    idx === pageIndex ? { ...p, status: 'generating' as const } : p
  )
  const allImageIds = new Set<string>()
  pages.forEach((p) => p.imageIds.forEach((id) => allImageIds.add(id)))
  const dataUrlMap: Record<string, string> = {}
  const sizeMap: Record<string, { width: number; height: number }> = {}
  await Promise.all(
    Array.from(allImageIds).map(async (imageId) => {
      const blobUrl = pageImageUrlCache.value[imageId]
      if (!blobUrl) return
      try {
        const [dataUrl, size] = await Promise.all([
          blobUrlToDataUrl(blobUrl),
          getImageDimensions(blobUrl).catch(() => ({ width: 400, height: 300 }))
        ])
        dataUrlMap[imageId] = dataUrl
        sizeMap[imageId] = size
      } catch {}
    })
  )
  const candidates = await buildCandidateImages()
  const descMap: Record<string, string> = Object.fromEntries(
    candidates.map((c) => [c.id, c.description || ''])
  )
  // 每风格一张图，所有页共用同一背景
  const background = style.background1 ?? style.background2 ?? style.background3 ?? ''
  let layoutHtml = ''
  const layoutCode = pageLayoutCodes.value[pageIndex] || layoutList.value[0]?.code || ''
  if (layoutCode) {
    try {
      const raw = await templateApi.getPreviewHtml(layoutCode)
      layoutHtml = typeof raw === 'string' ? raw : (raw as { data?: string })?.data ?? ''
    } catch {
      layoutHtml = ''
    }
  }
  const imagesForPage = (page.imageIds || []).map((id) => {
    const size = sizeMap[id] ?? { width: 400, height: 300 }
    return {
      description: descMap[id] ?? '',
      width: size.width,
      height: size.height
    }
  })
  let backgroundBase64: { base64: string; mediaType: string } | null = null
  if (background) {
    try {
      backgroundBase64 = await styleBackgroundHtmlToBase64(background)
    } catch {
      backgroundBase64 = null
    }
  }
  const contentImageBase64List: string[] = (page.imageIds || []).flatMap((id) => {
    const dataUrl = dataUrlMap[id]
    if (!dataUrl) return []
    const m = dataUrl.match(/^data:image\/[^;]+;base64,(.+)$/)
    const base64 = m ? m[1] : dataUrl.replace(/^data:image\/[^;]+;base64,/, '')
    return base64 ? [base64] : []
  })
  const revisionNote = opts?.revisionNote?.trim()
  const previousPageHtml =
    revisionNote && step4Pages.value[pageIndex]?.html
      ? step4Pages.value[pageIndex].html
      : ''
  let html: string
  try {
    const contentFragment = await generatePageHtml({
      theme: page.theme,
      textContent: page.textContent || '',
      images: imagesForPage,
      layoutHtml,
      styleBackgroundHtml: background,
      ...(backgroundBase64
        ? {
            backgroundImageBase64: backgroundBase64.base64,
            backgroundImageMediaType: backgroundBase64.mediaType
          }
        : {}),
      ...(imagesForPage.length > 0 && contentImageBase64List.length > 0
        ? { contentImageBase64List }
        : {}),
      ...(revisionNote
        ? {
            revisionInstructions: revisionNote,
            previousPageHtml: previousPageHtml || ''
          }
        : {})
    })
    let trimmed = stripMarkdownCodeFence(contentFragment || '')
    const backgroundImageDataUrl = backgroundBase64
      ? `data:${backgroundBase64.mediaType};base64,${backgroundBase64.base64}`
      : ''
    trimmed = trimmed.split(BACKGROUND_IMAGE_URL_PLACEHOLDER).join(backgroundImageDataUrl)
    if (trimmed) {
      const imageDataUrls = (page.imageIds || []).map((id) => dataUrlMap[id] ?? '')
      html = injectContentFragmentIntoStyleHtml(background, trimmed, imageDataUrls)
    } else {
      const imageDataUrls = (page.imageIds || [])
        .map((id) => dataUrlMap[id])
        .filter((dataUrl): dataUrl is string => !!dataUrl)
      html = injectContentIntoStyleHtml(background, page.theme, page.textContent || '', imageDataUrls)
    }
  } catch {
    const imageDataUrls = (page.imageIds || [])
      .map((id) => dataUrlMap[id])
      .filter((dataUrl): dataUrl is string => !!dataUrl)
    html = injectContentIntoStyleHtml(background, page.theme, page.textContent || '', imageDataUrls)
  }
  step4Pages.value = step4Pages.value.map((p, idx) =>
    idx === pageIndex ? { ...p, html, status: 'done' as const } : p
  )
  if (currentPptId.value && userId.value) {
    try {
      await updatePptGeneratedPages(userId.value, currentPptId.value, {
        pages: step4Pages.value.map((p) => ({ id: p.id, html: p.html }))
      })
    } catch {
      //
    }
  }
}

async function loadPptList() {
  if (!userId.value) {
    pptList.value = []
    return
  }
  pptListLoading.value = true
  try {
    const res = await listPpts(userId.value)
    pptList.value = Array.isArray(res?.data) ? res.data : []
  } catch {
    pptList.value = []
  } finally {
    pptListLoading.value = false
  }
}

async function loadKbListForWorkshop() {
  if (!userId.value) {
    kbListForWorkshop.value = []
    return
  }
  kbListLoading.value = true
  try {
    kbListForWorkshop.value = await listKnowledgeBases(userId.value)
  } catch {
    kbListForWorkshop.value = []
  } finally {
    kbListLoading.value = false
  }
}

// 页面加载或登录后拉取制作记录（含未完成），便于在「制作记录」中展示
watch(userId, () => {
  if (userId.value) void loadPptList()
  void loadKbListForWorkshop()
}, { immediate: true })

/** 从制作记录点击「继续」：先打开弹窗再恢复该创作 */
function handleContinue(pptId: string) {
  openWorkshop()
  void resumePpt(pptId)
}

async function handlePreview(pptId: string, e: Event) {
  e.stopPropagation()
  if (!userId.value) return
  previewModalOpen.value = true
  previewLoading.value = true
  previewError.value = ''
  previewTopic.value = ''
  previewPptId.value = pptId
  previewPages.value = []
  previewPageIndex.value = 0
  try {
    const res = await getPpt(userId.value, pptId)
    const state = res?.data as PptState | undefined
    previewTopic.value = state?.process?.topic ?? ''
    const gp = state?.generatedPages?.pages ?? []
    if (!Array.isArray(gp) || gp.length === 0) {
      previewError.value = '暂无可预览内容（尚未生成逐页页面）'
      return
    }
    previewPages.value = [...gp].sort((a, b) => (a.id ?? 0) - (b.id ?? 0))
  } catch (err) {
    previewError.value = (err as Error)?.message ?? '预览加载失败'
  } finally {
    previewLoading.value = false
  }
}

function closePreviewModal() {
  previewModalOpen.value = false
  previewLoading.value = false
  previewError.value = ''
  previewTopic.value = ''
  previewPptId.value = null
  previewPages.value = []
  previewPageIndex.value = 0
}

function previewPrevPage() {
  if (previewPageIndex.value <= 0) return
  previewPageIndex.value -= 1
}

function previewNextPage() {
  if (previewPageIndex.value >= previewPages.value.length - 1) return
  previewPageIndex.value += 1
}

/** 删除一份制作记录 */
async function handleDeletePpt(pptId: string, e: Event) {
  e.stopPropagation()
  if (!userId.value) return
  if (!confirm('确定删除该条制作记录？删除后不可恢复。')) return
  try {
    const res = await deletePpt(userId.value, pptId)
    if (res?.code === 200) {
      if (currentPptId.value === pptId) {
        currentPptId.value = null
        closeModal()
      }
      void loadPptList()
    } else {
      alert(res?.message ?? '删除失败')
    }
  } catch (err) {
    alert((err as Error)?.message ?? '删除失败')
  }
}

/** 从服务端恢复一份未完成的创作 */
async function resumePpt(pptId: string) {
  if (!userId.value) return
  try {
    const res = await getPpt(userId.value, pptId)
    const state = res?.data
    if (!state) return
    currentPptId.value = pptId
    step1Topic.value = state.process?.topic ?? ''
    selectedPdfIds.value = state.upload?.pdfIds ?? []
    selectedImageIds.value = state.upload?.imageIds ?? []
    step2Outline.value = state.outline?.content ?? ''
    step2OutlineMode.value = 'preview'
    const pages = state.pageContents?.pages ?? []
    step3PageContents.value = pages.map((p) => ({
      theme: p.theme ?? '',
      textContent: p.textContent ?? '',
      imageIds: p.imageIds ?? [],
      pageType: p.pageType
    }))
    pageLayoutCodes.value = state.layoutCodes?.codes ?? []
    if (pageLayoutCodes.value.length !== step3PageContents.value.length) {
      pageLayoutCodes.value = Array.from(
        { length: step3PageContents.value.length },
        (_, i) => pageLayoutCodes.value[i] ?? ''
      )
    }
    selectedStyleId.value = state.style?.styleId ?? ''
    const gp = state.generatedPages?.pages
    if (gp && Array.isArray(gp) && gp.length === step3PageContents.value.length) {
      step4Pages.value = gp.map((p) => ({
        id: p.id,
        html: p.html ?? '',
        status: 'done' as const
      }))
      step4CurrentPageIndex.value = 0
    } else {
      step4Pages.value = []
      step4CurrentPageIndex.value = 0
    }
    const step = Math.min(6, Math.max(0, state.process?.currentStep ?? 0))
    currentStep.value = step as StepIndex
    maxStepReached.value = step
    returnSnapshot.value = null
    workshopDocumentContents.value = []
    if (selectedPdfIds.value.length > 0) {
      const contents: string[] = []
      for (const fileId of selectedPdfIds.value) {
        try {
          const r = await getPdfContent(userId.value, fileId)
          const c = (r?.data?.content ?? '').trim()
          if (c) contents.push(c)
        } catch {
          //
        }
      }
      workshopDocumentContents.value = contents
    }
    if (step3PageContents.value.length > 0) {
      await buildCandidateImages()
    }
  } catch (e) {
    console.warn('恢复 PPT 创作失败', e)
  }
}

function openWorkshop() {
  showModal.value = true
  currentStep.value = 0
  maxStepReached.value = 0
  returnSnapshot.value = null
  currentPptId.value = null
  selectedPdfIds.value = []
  selectedImageIds.value = []
  step1PdfSelect.value = ''
  step1ImageSelect.value = ''
  step1Topic.value = ''
  step2Outline.value = ''
  step2Error.value = ''
  workshopDocumentContents.value = []
  step3PageContents.value = []
  step3Error.value = ''
  step3CandidateImageLabels.value = {}
  step3CandidateImageIds.value = []
  selectedStyleId.value = ''
  styleList.value = []
  step3StyleError.value = ''
  stylePreviewDetail.value = null
  styleListPage.value = 0
  step4Pages.value = []
  step4Error.value = ''
  step4StyleDetail.value = null
  layoutList.value = []
  pageLayoutCodes.value = []
  stepLayoutError.value = ''
  stepSaveError.value = ''
  loadStep1Options()
  loadStep3Styles()
  void loadPptList()
}

function closeModal() {
  showModal.value = false
}

/** 在进入下一步前将当前步骤持久化到服务端；返回是否成功 */
async function saveCurrentStepIfNeeded(): Promise<boolean> {
  const uid = userId.value
  const pptId = currentPptId.value
  const step = currentStep.value
  if (step === 0) {
    if (!uid) return false
    // 与形成大纲一致：下拉框已选但未点「添加」的也一并计入
    const pdfIds = [...selectedPdfIds.value]
    if (step1PdfSelect.value && !pdfIds.includes(step1PdfSelect.value)) pdfIds.push(step1PdfSelect.value)
    const imageIds = [...selectedImageIds.value]
    if (step1ImageSelect.value && !imageIds.includes(step1ImageSelect.value)) imageIds.push(step1ImageSelect.value)
    try {
      if (!pptId) {
        const res = await createPpt(uid, {
          topic: step1Topic.value.trim(),
          pdfIds,
          imageIds
        })
        const id = res?.data?.pptId
        if (!id) {
          stepSaveError.value = '创建失败，未返回 pptId'
          return false
        }
        currentPptId.value = id
        await updatePptProcess(uid, id, { currentStep: 1, topic: step1Topic.value.trim() })
      } else {
        await updatePptUpload(uid, pptId, {
          pdfIds,
          imageIds
        })
        await updatePptProcess(uid, pptId, { currentStep: 1, topic: step1Topic.value.trim() })
      }
      return true
    } catch (e) {
      stepSaveError.value = (e as Error)?.message ?? '保存失败'
      return false
    }
  }
  if (!uid || !pptId) return true
  try {
    if (step === 1) {
      await updatePptOutline(uid, pptId, { content: step2Outline.value })
      await updatePptProcess(uid, pptId, { currentStep: 2 })
    } else if (step === 2) {
      await updatePptPageContents(uid, pptId, {
        pages: step3PageContents.value.map((p) => ({
          theme: p.theme,
          textContent: p.textContent ?? '',
          imageIds: p.imageIds ?? [],
          pageType: p.pageType
        }))
      })
      await updatePptProcess(uid, pptId, { currentStep: 3 })
    } else if (step === 3) {
      await updatePptLayoutCodes(uid, pptId, { codes: [...pageLayoutCodes.value] })
      await updatePptProcess(uid, pptId, { currentStep: 4 })
    } else if (step === 4) {
      await updatePptStyle(uid, pptId, { styleId: selectedStyleId.value })
      await updatePptProcess(uid, pptId, { currentStep: 5 })
    } else if (step === 5) {
      await updatePptProcess(uid, pptId, { currentStep: 6 })
    }
    return true
  } catch (e) {
    stepSaveError.value = (e as Error)?.message ?? '保存失败'
    return false
  }
}

/** 取当前某步状态的快照（用于回退后检测是否修改） */
function getStepSnapshot(stepIndex: number): unknown {
  if (stepIndex === 0) {
    return {
      topic: step1Topic.value,
      pdfIds: [...selectedPdfIds.value],
      imageIds: [...selectedImageIds.value]
    }
  }
  if (stepIndex === 1) return { outline: step2Outline.value }
  if (stepIndex === 2) return { pageContents: JSON.parse(JSON.stringify(step3PageContents.value)) }
  if (stepIndex === 3) return { layoutCodes: [...pageLayoutCodes.value] }
  if (stepIndex === 4) return { styleId: selectedStyleId.value }
  return null
}

/** 当前步骤状态与快照是否一致（用于判断回退后是否做了修改） */
function stepStateEquals(stepIndex: number, snapshot: unknown): boolean {
  const current = getStepSnapshot(stepIndex)
  return JSON.stringify(current) === JSON.stringify(snapshot)
}

/** 舍弃第 k 步之后的所有结果：清空本地状态并同步到后端（process + 各步 JSON） */
async function discardStepsAfter(k: number) {
  const uid = userId.value
  const pptId = currentPptId.value

  if (k < 1) {
    step2Outline.value = ''
    step2OutlineMode.value = 'preview'
  }
  if (k < 2) {
    step3PageContents.value = []
    pageLayoutCodes.value = []
    step3Error.value = ''
    step3CandidateImageLabels.value = {}
  }
  if (k < 3) {
    pageLayoutCodes.value = []
    stepLayoutError.value = ''
  }
  if (k < 4) {
    selectedStyleId.value = ''
  }
  if (k < 5) {
    step4Pages.value = []
    step4Error.value = ''
    step4StyleDetail.value = null
  }

  if (!uid || !pptId) return
  try {
    if (k < 1) await updatePptOutline(uid, pptId, { content: '' })
    if (k < 2) await updatePptPageContents(uid, pptId, { pages: [] })
    if (k < 3) await updatePptLayoutCodes(uid, pptId, { codes: [] })
    if (k < 4) await updatePptStyle(uid, pptId, { styleId: '' })
    if (k < 5) await updatePptGeneratedPages(uid, pptId, { pages: [] })
    await updatePptProcess(uid, pptId, { currentStep: k + 1 })
  } catch (e) {
    console.warn('舍弃后续步骤并同步后端失败', e)
  }
}

async function goNext() {
  if (!canGoNext()) return
  stepSaveError.value = ''
  const step = currentStep.value

  if (returnSnapshot.value && returnSnapshot.value.stepIndex === step) {
    if (!stepStateEquals(step, returnSnapshot.value.data)) {
      await discardStepsAfter(step)
      maxStepReached.value = step
    }
    returnSnapshot.value = null
  }

  const ok = await saveCurrentStepIfNeeded()
  if (!ok) return
  if (currentStep.value < 6) currentStep.value = (currentStep.value + 1) as StepIndex
  maxStepReached.value = Math.max(maxStepReached.value, currentStep.value)
  void loadPptList()
}

function goPrev() {
  if (currentStep.value <= 0) return
  const nextStep = (currentStep.value - 1) as StepIndex
  currentStep.value = nextStep
  if (nextStep < maxStepReached.value) {
    returnSnapshot.value = { stepIndex: nextStep, data: getStepSnapshot(nextStep) }
  }
}

function canGoNext(): boolean {
  if (currentStep.value === 0) return !!step1Topic.value.trim()
  if (currentStep.value === 1) return !!step2Outline.value.trim()
  if (currentStep.value === 2) {
    return (
      step3PageContents.value.length > 0 &&
      !step3Loading.value &&
      step3PageRegeneratingIndex.value === null
    )
  }
  if (currentStep.value === 3) {
    return (
      step3PageContents.value.length > 0 &&
      pageLayoutCodes.value.length === step3PageContents.value.length &&
      pageLayoutCodes.value.every((c) => c != null && String(c).trim() !== '')
    )
  }
  if (currentStep.value === 4) return !!selectedStyleId.value
  if (currentStep.value === 5) return step4Pages.value.length > 0 && step4Pages.value.every((p) => p.status === 'done')
  return true
}

// 仅前端测试：不调 LLM，直接填入默认大纲以便进入下一步
const DEFAULT_OUTLINE = `一、封面与开场
  - 标题页
  - 副标题与演讲人

二、背景与目标
  - 项目背景简述
  - 本次汇报目标

三、核心内容
  - 要点一
  - 要点二
  - 要点三

四、总结与下一步
  - 核心结论
  - 后续计划

五、Q&A`

async function generateOutlineClick() {
  const topic = step1Topic.value.trim()
  if (!topic) {
    step2Error.value = '请先在第一步填写主题/需求说明'
    return
  }
  if (!userId.value) {
    step2Error.value = '请先登录'
    return
  }
  step2Loading.value = true
  step2Error.value = ''
  // 若只在下拉框选了文档但没点「添加」，这里自动视为已选，避免传 0 个文档
  const idsToFetch =
    selectedPdfIds.value.length > 0
      ? [...selectedPdfIds.value]
      : step1PdfSelect.value
        ? [step1PdfSelect.value]
        : []
  if (idsToFetch.length > 0 && selectedPdfIds.value.length === 0) {
    selectedPdfIds.value = idsToFetch
  }
  try {
    const documentContents: string[] = []
    const failedPdfIds: string[] = []
    console.log('[形成大纲] 将拉取文档数:', idsToFetch.length, 'fileIds:', idsToFetch)
    for (const fileId of idsToFetch) {
      try {
        const res = await getPdfContent(userId.value, fileId)
        const content = (res?.data?.content ?? (res as { data?: { content?: string } })?.data?.content ?? '').trim()
        if (content) documentContents.push(content)
        else failedPdfIds.push(fileId)
      } catch (e) {
        failedPdfIds.push(fileId)
        const err = e as { response?: { status?: number; data?: unknown }; message?: string }
        console.warn(
          '[形成大纲] 拉取 content.md 失败:',
          fileId,
          'status:',
          err.response?.status,
          'message:',
          err.response?.data ?? err.message,
          e
        )
      }
    }
    if (idsToFetch.length > 0 && documentContents.length === 0) {
      step2Error.value = '参考文档内容拉取失败：请确认 document-service 已启动（端口 8085），且所选 PDF 已解析完成（状态为已解析）。打开浏览器控制台可查看具体错误。'
      step2Loading.value = false
      return
    }
    if (failedPdfIds.length > 0) {
      step2Error.value = `已使用 ${documentContents.length} 个文档生成大纲；${failedPdfIds.length} 个拉取失败，请检查 document-service 与解析状态。`
    }
    let ragContent = ''
    let externalContent = ''
    const materialSummary = documentContents
      .slice(0, 3)
      .map((c) => (c || '').slice(0, 4000))
      .join('\n---\n')
    try {
      const judge = await judgeOutlineRetrieve({
        topic,
        materialSummary,
        hasKb: !!selectedKbId.value
      })
      if (judge.need_search && judge.keywords.length > 0) {
        const types = new Set(judge.search_types.map((t) => t.toLowerCase()))
        if (types.has('rag') && selectedKbId.value) {
          ragContent = await gatherRagSnippets(userId.value, selectedKbId.value, judge.keywords.slice(0, 3))
        }
        if (types.has('web')) {
          externalContent = await gatherWebSummary(judge.keywords.slice(0, 3))
        }
      }
    } catch (e) {
      console.warn('[形成大纲] 检索判定或拉取失败，继续无检索增强', e)
    }
    const res = await generateOutlineFromWorkshop(topic, documentContents, {
      ragContent,
      externalContent
    })
    const text = getContentFromResponse(res?.data)
    step2Outline.value = text ?? res?.data?.content ?? ''
    step2OutlineMode.value = 'preview'
    workshopDocumentContents.value = documentContents
    if (!step2Outline.value) step2Error.value = res?.message ?? '未返回大纲内容'
    else {
      // 仅发送一次：根据大纲生成整份 PPT 每页主题（开头、目录、分页、结束页），结果在 llm-service 后端打印
      void generateFullPageThemes(step2Outline.value)
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    step2Error.value = String(msg ?? '生成大纲失败')
  } finally {
    step2Loading.value = false
  }
}

/** 解析「每页主题」JSON（来自 ppt-full-page-themes），得到带 type 的列表；失败则返回空 */
function parseFullPageThemes(raw: string): Array<{ type: string; theme: string }> {
  const trimmed = raw?.trim() ?? ''
  if (!trimmed) return []
  let jsonStr = trimmed
  const codeBlock = trimmed.match(/^```(?:json)?\s*([\s\S]*?)```\s*$/m)
  if (codeBlock?.[1]) jsonStr = codeBlock[1].trim()
  try {
    const arr = JSON.parse(jsonStr) as unknown
    if (!Array.isArray(arr)) return []
    return arr.map((item: Record<string, unknown>) => {
      const type = String(item.type ?? '内容页')
      const content = String(item.content ?? '')
      const part = item.part as string | undefined
      const theme = part ? `${part} ${content}`.trim() : content
      return { type, theme }
    }).filter((p) => p.theme.trim())
  } catch {
    return []
  }
}

/** 合并参考文档图片（pdf|fileId|id）+ 单独上传的图片，并更新 step3CandidateImageLabels */
async function buildCandidateImages(): Promise<Array<{ id: string; description: string }>> {
  const uid = userId.value
  if (!uid) return []
  const pdfCandidates: Array<{ id: string; description: string }> = []
  for (const fileId of selectedPdfIds.value) {
    try {
      const res = await getPdfImages(uid, fileId)
      const list = Array.isArray(res?.data) ? res.data : []
      for (const img of list) {
        const id = `pdf|${fileId}|${img.id}`
        pdfCandidates.push({ id, description: img.description || img.id })
      }
    } catch {
      /* 忽略单个 PDF 拉取失败 */
    }
  }
  const userCandidates = imageList.value
    .filter((img) => selectedImageIds.value.includes(img.imageId))
    .map((img) => ({ id: img.imageId, description: img.description || img.fileName || '' }))
  const candidates = [...pdfCandidates, ...userCandidates]
  step3CandidateImageLabels.value = Object.fromEntries(
    candidates.map((c) => [c.id, c.description || c.id])
  )
  step3CandidateImageIds.value = candidates.map((c) => c.id)
  return candidates
}

/** 单页：LLM 判定是否检索 + 拉取 RAG/外部摘要，作为额外参考块 */
async function buildPageExtraReferenceBlocks(pageTheme: string, pageType: string): Promise<string[]> {
  const uid = userId.value
  if (!uid) return []
  try {
    const judge = await judgePageRetrieve({
      topic: step1Topic.value.trim(),
      pageTheme,
      pageType: pageType || '内容页',
      outlineSnippet: (step2Outline.value || '').slice(0, 2000),
      hasKb: !!selectedKbId.value
    })
    if (!judge.need_search || judge.keywords.length === 0) return []
    const blocks: string[] = []
    const types = new Set(judge.search_types.map((t) => t.toLowerCase()))
    if (types.has('rag') && selectedKbId.value) {
      const rag = await gatherRagSnippets(uid, selectedKbId.value, judge.keywords)
      if (rag) blocks.push(rag)
    }
    if (types.has('web')) {
      const web = await gatherWebSummary(judge.keywords)
      if (web) blocks.push(web)
    }
    return blocks
  } catch (e) {
    console.warn('[每页内容] 检索判定或拉取失败', e)
    return []
  }
}

async function generatePageContentsClick() {
  const refDocs = workshopDocumentContents.value
  const candidateImages = await buildCandidateImages()
  step3Loading.value = true
  step3Error.value = ''
  step3PageContents.value = []
  try {
    // 优先使用「每页主题」结果（带 type），以便按封面/目录/分页/内容/结束选用不同提示词
    let pagesWithType: Array<{ type: string; theme: string }> = []
    try {
      const themesJson = await generateFullPageThemes(step2Outline.value)
      if (themesJson) pagesWithType = parseFullPageThemes(themesJson)
    } catch {
      /* 忽略，使用大纲解析兜底 */
    }
    if (pagesWithType.length === 0) {
      const slides = parseOutlineToSlides(step2Outline.value)
      if (slides.length === 0) {
        step3Error.value = '请先形成大纲，或大纲格式无法解析'
        return
      }
      pagesWithType = slides.map((s) => ({
        type: '内容页',
        theme: s.part ? `${s.part} ${s.title}`.trim() : s.title
      }))
    }
    for (const page of pagesWithType) {
      const extraRefs = await buildPageExtraReferenceBlocks(page.theme, page.type)
      const textContent = await generatePageContent(page.theme, refDocs, page.type, extraRefs)
      const imageIds = await selectPageImages(textContent, candidateImages)
      step3PageContents.value.push({
        theme: page.theme,
        textContent: textContent.trim(),
        imageIds,
        pageType: page.type
      })
    }
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    step3Error.value = String(msg ?? '形成每页内容失败')
  } finally {
    step3Loading.value = false
  }
}

/** 仅重新生成某一页内容 */
async function regenerateOnePage(idx: number) {
  const page = step3PageContents.value[idx]
  if (!page || step3Loading.value || step3PageRegeneratingIndex.value !== null) return
  const refDocs = workshopDocumentContents.value
  const candidateImages = await buildCandidateImages()
  step3PageRegeneratingIndex.value = idx
  step3Error.value = ''
  try {
    const extraRefs = await buildPageExtraReferenceBlocks(page.theme, page.pageType || '内容页')
    const textContent = await generatePageContent(page.theme, refDocs, page.pageType, extraRefs)
    const imageIds = await selectPageImages(textContent, candidateImages)
    step3PageContents.value = step3PageContents.value.map((p, i) =>
      i === idx
        ? { ...p, textContent: textContent.trim(), imageIds }
        : p
    )
  } catch (e: unknown) {
    const msg = e && typeof e === 'object' && 'response' in e
      ? (e as { response?: { data?: { message?: string } } }).response?.data?.message
      : (e as Error)?.message
    step3Error.value = String(msg ?? '该页重新生成失败')
  } finally {
    step3PageRegeneratingIndex.value = null
  }
}

function deleteOnePage(idx: number) {
  if (step3Loading.value || step3PageRegeneratingIndex.value !== null) return
  if (step3PageContents.value.length <= 1) {
    step3Error.value = '至少需要保留 1 页，才能进入下一步'
    return
  }
  const page = step3PageContents.value[idx]
  if (!page) return
  const ok = window.confirm(`确认删除第 ${idx + 1} 页吗？`)
  if (!ok) return

  step3Error.value = ''
  step3PageContents.value = step3PageContents.value.filter((_, i) => i !== idx)
  // 同步删除对应的版式选择（若已进入后续步骤，后续会要求重新分配）
  if (pageLayoutCodes.value.length > idx) {
    pageLayoutCodes.value = pageLayoutCodes.value.filter((_, i) => i !== idx)
  }
  // 页数变化后，后续步骤内容不再可靠，直接清空让用户重新选择
  step4Pages.value = []
  step4Error.value = ''
  step4StyleDetail.value = null
  selectedStyleId.value = ''
}
/** 每页最多可选的图片数（与 AI 一致并可手动增删） */
const STEP3_MAX_IMAGES_PER_PAGE = 9
/** 添加图片弹窗内每页显示的候选数量 */
const ADD_IMAGE_MODAL_PAGE_SIZE = 12

const addImageModalOpen = ref(false)
const addImageModalPageIdx = ref(0)
const addImageModalPaginationPage = ref(1)
/** 当前放大预览的图片 id，非空时显示大图层，点击缩略图设为该值 */
const addImageModalPreviewId = ref<string | null>(null)

const addImageModalCandidateIds = computed(() => {
  const idx = addImageModalPageIdx.value
  const page = step3PageContents.value[idx]
  if (!page) return []
  return step3CandidateImageIds.value.filter((id) => !page.imageIds.includes(id))
})

const addImageModalTotalPages = computed(() =>
  Math.max(1, Math.ceil(addImageModalCandidateIds.value.length / ADD_IMAGE_MODAL_PAGE_SIZE))
)

const addImageModalCurrentPageIds = computed(() => {
  const list = addImageModalCandidateIds.value
  const page = addImageModalPaginationPage.value
  const start = (page - 1) * ADD_IMAGE_MODAL_PAGE_SIZE
  return list.slice(start, start + ADD_IMAGE_MODAL_PAGE_SIZE)
})

function openAddImageModal(pageIdx: number) {
  addImageModalPageIdx.value = pageIdx
  addImageModalPaginationPage.value = 1
  addImageModalPreviewId.value = null
  addImageModalOpen.value = true
  loadBlobsForImageIds(step3CandidateImageIds.value)
}

function closeAddImageModal() {
  addImageModalOpen.value = false
  addImageModalPreviewId.value = null
}

function showAddImagePreview(imageId: string) {
  addImageModalPreviewId.value = imageId
}

function closeAddImagePreview() {
  addImageModalPreviewId.value = null
}

function addPageImageFromModal(imageId: string) {
  const idx = addImageModalPageIdx.value
  addPageImage(idx, imageId)
  closeAddImagePreview()
  const page = step3PageContents.value[idx]
  if (page && page.imageIds.length >= STEP3_MAX_IMAGES_PER_PAGE) {
    closeAddImageModal()
  }
}

function removePageImage(pageIdx: number, imageId: string) {
  const page = step3PageContents.value[pageIdx]
  if (!page) return
  page.imageIds = page.imageIds.filter((id) => id !== imageId)
}

function addPageImage(pageIdx: number, imageId: string) {
  const page = step3PageContents.value[pageIdx]
  if (!page || !imageId || page.imageIds.length >= STEP3_MAX_IMAGES_PER_PAGE) return
  if (page.imageIds.includes(imageId)) return
  page.imageIds = [...page.imageIds, imageId]
}

/** 为指定 id 列表加载 blob 到 pageImageUrlCache（供弹窗缩略图等使用） */
function loadBlobsForImageIds(ids: string[]) {
  const uid = userId.value
  if (!uid) return
  ids.forEach((imageId) => {
    if (pageImageUrlCache.value[imageId]) return
    if (imageId.startsWith('pdf|')) {
      const parts = imageId.split('|')
      if (parts.length >= 3) {
        const fileId = parts[1]
        if (!fileId) return
        const pdfImageId = parts.slice(2).join('|')
        getPdfImageFileBlob(uid, fileId, pdfImageId)
          .then((blob) => {
            if (!blob) return
            const url = URL.createObjectURL(blob)
            pageImageUrlCache.value = { ...pageImageUrlCache.value, [imageId]: url }
          })
          .catch(() => {})
      }
      return
    }
    getImageFileBlob(uid, imageId)
      .then((blob) => {
        if (!blob) return
        const url = URL.createObjectURL(blob)
        pageImageUrlCache.value = { ...pageImageUrlCache.value, [imageId]: url }
      })
      .catch(() => {})
  })
}

// 逐页生成改为点击按钮触发，不再在进入步骤时自动执行

function saveProject() {
  // 暂无后端，仅模拟：加入历史并关闭
  historyList.value = [
    {
      id: String(Date.now()),
      title: step1Topic.value.trim() || '未命名演示',
      updatedAt: new Date().toLocaleString('zh-CN')
    },
    ...historyList.value
  ]
  closeModal()
}

</script>

<template>
  <div class="page">
    <div class="card">
      <div class="head-row">
        <div>
          <h1 class="title">制作工坊</h1>
          <p class="desc">从内容上传到逐页生成，完成 PPT 制作流程。</p>
        </div>
        <button type="button" class="btn-primary" @click="openWorkshop">制作新的 PPT</button>
      </div>

      <section class="history-section">
        <h2 class="section-title">制作记录</h2>
        <p v-if="pptListLoading" class="empty-hint">加载中…</p>
        <ul v-else-if="pptList.length" class="history-list">
          <li v-for="item in pptList" :key="item.pptId" class="history-item history-item-with-action">
            <span class="history-title">{{ item.topic || '未命名' }}</span>
            <span class="history-meta">
              {{ item.currentStep >= 6 ? '已完成' : `未完成 · 步骤 ${item.currentStep + 1}/7` }}
              · {{ item.updatedAt ? new Date(item.updatedAt).toLocaleString('zh-CN') : '' }}
            </span>
            <button
              v-if="item.currentStep >= 6"
              type="button"
              class="btn-small btn-ghost"
              :disabled="previewLoading && previewPptId === item.pptId"
              @click="handlePreview(item.pptId, $event)"
            >
              预览
            </button>
            <button
              type="button"
              class="btn-small"
              @click="handleContinue(item.pptId)"
            >
              {{ item.currentStep >= 6 ? '编辑' : '继续' }}
            </button>
            <button
              type="button"
              class="btn-small btn-ghost"
              title="删除"
              @click="handleDeletePpt(item.pptId, $event)"
            >
              删除
            </button>
          </li>
        </ul>
        <p v-else class="empty-hint">暂无记录，点击「制作新的 PPT」开始</p>
      </section>
    </div>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="showModal" class="modal-mask" @click.self="closeModal">
          <div class="workshop-modal">
            <div class="modal-head">
              <span class="modal-title">制作新的 PPT</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closeModal">×</button>
            </div>

            <div class="steps-bar">
              <div
                v-for="(label, i) in STEPS"
                :key="i"
                class="step-node"
                :class="{ active: currentStep === i, done: currentStep > i }"
              >
                <span class="step-num">{{ i + 1 }}</span>
                <span class="step-label">{{ label }}</span>
                <span v-if="i < STEPS.length - 1" class="step-arrow">→</span>
              </div>
            </div>

            <div class="modal-body">
              <!-- Step 1: 内容上传（下拉选择已解析 PDF 与图片） -->
              <div v-show="currentStep === 0" class="step-content">
                <p class="step-desc">从文件管理中选择已解析的文档和图片，作为 PPT 内容来源。</p>
                <div v-if="!userId" class="step1-hint">请先登录</div>
                <template v-else>
                  <div class="step1-block">
                    <h4 class="step1-subtitle">选择文档（仅已解析）</h4>
                    <p v-if="step1PdfLoading" class="loading-hint">加载中…</p>
                    <template v-else>
                      <p v-if="parsedPdfList.length === 0" class="step1-empty">暂无已解析的 PDF，请先在文件管理中上传并解析。</p>
                      <div v-else class="step1-dropdown-row">
                        <select v-model="step1PdfSelect" class="step1-select">
                          <option value="">请选择文档</option>
                          <option
                            v-for="p in parsedPdfList"
                            :key="p.fileId"
                            :value="p.fileId"
                          >
                            {{ p.pdfFileName || p.fileId }}
                          </option>
                        </select>
                        <button
                          type="button"
                          class="btn-add"
                          :disabled="!step1PdfSelect || selectedPdfIds.includes(step1PdfSelect)"
                          @click="addSelectedPdf"
                        >
                          添加
                        </button>
                      </div>
                      <p class="step1-hint-inline">选择后请点击「添加」加入参考列表，形成大纲时会使用这些文档的内容。</p>
                      <div v-if="selectedPdfIds.length > 0" class="step1-chips">
                        <span
                          v-for="id in selectedPdfIds"
                          :key="id"
                          class="chip"
                        >
                          {{ pdfLabel(id) }}
                          <button type="button" class="chip-remove" aria-label="移除" @click="removePdf(id)">×</button>
                        </span>
                      </div>
                    </template>
                  </div>
                  <div class="step1-block">
                    <h4 class="step1-subtitle">选择图片</h4>
                    <p v-if="step1ImageLoading" class="loading-hint">加载中…</p>
                    <template v-else>
                      <p v-if="imageList.length === 0" class="step1-empty">暂无图片，请先在文件管理中上传。</p>
                      <div v-else class="step1-dropdown-row">
                        <select v-model="step1ImageSelect" class="step1-select">
                          <option value="">请选择图片</option>
                          <option
                            v-for="img in imageList"
                            :key="img.imageId"
                            :value="img.imageId"
                            :title="img.description || undefined"
                          >
                            {{ img.fileName || img.imageId }}
                          </option>
                        </select>
                        <button
                          type="button"
                          class="btn-add"
                          :disabled="!step1ImageSelect || selectedImageIds.includes(step1ImageSelect)"
                          @click="addSelectedImage"
                        >
                          添加
                        </button>
                      </div>
                      <div v-if="selectedImageIds.length > 0" class="step1-chips">
                        <span
                          v-for="id in selectedImageIds"
                          :key="id"
                          class="chip"
                        >
                          {{ imageLabel(id) }}
                          <button type="button" class="chip-remove" aria-label="移除" @click="removeImage(id)">×</button>
                        </span>
                      </div>
                    </template>
                  </div>
                  <div class="step1-block">
                    <h4 class="step1-subtitle">知识库（可选，按需检索）</h4>
                    <p v-if="kbListLoading" class="loading-hint">加载知识库列表…</p>
                    <select v-else v-model="selectedKbId" class="step1-select">
                      <option value="">不使用知识库检索</option>
                      <option v-for="kb in kbListForWorkshop" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
                    </select>
                    <p class="step1-hint-inline">
                      选择后，形成大纲与每页内容时会先由模型判定是否需要检索；非必要不调用 RAG/外部摘要。
                    </p>
                  </div>
                  <div class="topic-row">
                    <label>主题或需求说明（必填）：</label>
                    <input v-model="step1Topic" type="text" class="topic-input" placeholder="例如：产品发布会大纲" />
                  </div>
                </template>
              </div>

              <!-- Step 2: 形成大纲 -->
              <div v-show="currentStep === 1" class="step-content">
                <p class="step-desc">根据主题请求 LLM 生成大纲，生成后可在下方直接修改；点击「下一步」即保存当前修改并进入下一步。</p>
                <div class="outline-actions">
                  <button type="button" class="btn-primary" :disabled="step2Loading" @click="generateOutlineClick">
                    {{ step2Loading ? '生成中…' : (step2Outline ? '重新生成' : '形成大纲') }}
                  </button>
                </div>
                <p v-if="step2Error" class="error-msg">{{ step2Error }}</p>
                <!-- 有内容时：预览与编辑二选一，默认预览 -->
                <template v-if="step2Outline">
                  <div v-show="step2OutlineMode === 'preview'" class="outline-preview-wrap">
                    <div
                      v-if="step2OutlineRenderedHtml"
                      class="outline-preview markdown-body"
                      v-html="step2OutlineRenderedHtml"
                    />
                    <div v-else class="outline-preview outline-placeholder">格式解析后预览将显示在此处</div>
                  </div>
                  <div v-show="step2OutlineMode === 'edit'" class="outline-edit-wrap outline-edit-fixed">
                    <label class="outline-edit-label">大纲原文（可编辑）</label>
                    <textarea
                      v-model="step2Outline"
                      class="outline-textarea"
                      placeholder="在此修改大纲内容"
                      spellcheck="false"
                    />
                  </div>
                  <div class="outline-mode-toggle">
                    <button
                      type="button"
                      class="btn-ghost"
                      @click="step2OutlineMode = step2OutlineMode === 'preview' ? 'edit' : 'preview'"
                    >
                      {{ step2OutlineMode === 'preview' ? '编辑模式' : '预览模式' }}
                    </button>
                  </div>
                </template>
                <!-- 无内容时：仅显示编辑区 -->
                <div v-else class="outline-edit-wrap">
                  <label class="outline-edit-label">大纲原文（可编辑）</label>
                  <textarea
                    v-model="step2Outline"
                    class="outline-textarea"
                    placeholder="点击「形成大纲」生成后，可在此直接修改；生成后可切换「预览模式」/「编辑模式」查看。"
                    spellcheck="false"
                  />
                </div>
              </div>

              <!-- Step 3: 形成每页内容 -->
              <div v-show="currentStep === 2" class="step-content">
                <p class="step-desc">根据大纲与参考文档，AI 生成每页文字并选择配图。生成后可直接编辑文字、手动增删本页图片（选中的图片以缩略图显示），本步不排版。</p>
                <div class="outline-actions">
                  <button
                    type="button"
                    class="btn-primary"
                    :disabled="step3Loading"
                    @click="generatePageContentsClick"
                  >
                    {{ step3Loading ? '生成中…' : (step3PageContents.length ? '重新生成' : '形成每页内容') }}
                  </button>
                </div>
                <p v-if="step3Error" class="error-msg">{{ step3Error }}</p>
                <div v-if="step3PageContents.length" class="page-contents-preview">
                  <div
                    v-for="(page, idx) in step3PageContents"
                    :key="idx"
                    class="page-content-card"
                  >
                    <div class="page-content-card-head">
                      <h4 class="page-content-theme">第 {{ idx + 1 }} 页 — {{ page.theme }}</h4>
                      <div class="page-content-card-actions">
                        <button
                          type="button"
                          class="btn-small danger"
                          :disabled="step3Loading || step3PageRegeneratingIndex !== null || step3PageContents.length <= 1"
                          @click="deleteOnePage(idx)"
                        >
                          删除整页
                        </button>
                        <button
                          type="button"
                          class="btn-small"
                          :disabled="step3Loading || step3PageRegeneratingIndex !== null"
                          @click="regenerateOnePage(idx)"
                        >
                          {{ step3PageRegeneratingIndex === idx ? '生成中…' : '重新生成' }}
                        </button>
                      </div>
                    </div>
                    <div v-if="step3PageRegeneratingIndex === idx" class="page-content-loading-hint">正在生成该页…</div>
                    <textarea
                      v-else
                      v-model="page.textContent"
                      class="page-content-text page-content-text-edit"
                      rows="6"
                      placeholder="本页文字内容，可直接编辑"
                    />
                    <div class="page-content-images-row">
                      <div class="page-content-images">
                        <template v-for="imageId in page.imageIds" :key="imageId">
                          <div class="page-content-img-wrap">
                            <img
                              v-if="pageImageUrlCache[imageId]"
                              :src="pageImageUrlCache[imageId]"
                              :alt="imageLabel(imageId)"
                              class="page-content-img"
                            />
                            <div v-else class="page-content-img-loading" />
                            <button
                              type="button"
                              class="page-content-img-remove"
                              title="删除该图片"
                              @click="removePageImage(idx, imageId)"
                            >
                              ×
                            </button>
                          </div>
                        </template>
                        <button
                          v-if="page.imageIds.length < STEP3_MAX_IMAGES_PER_PAGE && step3CandidateImageIds.length > 0"
                          type="button"
                          class="page-content-add-image-btn"
                          @click="openAddImageModal(idx)"
                        >
                          + 添加图片
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Step 4: 选择版式（根据语义从版式管理为每页选择版式） -->
              <div v-show="currentStep === 3" class="step-content">
                <p class="step-desc">根据每页语义，从版式管理中为每一页选择版式；可一键智能分配或手动逐页选择。</p>
                <p v-if="stepLayoutError" class="error-msg">{{ stepLayoutError }}</p>
                <div v-else-if="stepLayoutLoading && layoutList.length === 0" class="loading-hint">加载版式列表中…</div>
                <template v-else-if="step3PageContents.length > 0">
                  <div class="layout-actions">
                    <button
                      type="button"
                      class="btn-primary"
                      :disabled="stepLayoutLoading || layoutList.length === 0"
                      @click="assignLayoutsClick"
                    >
                      {{ stepLayoutLoading ? '分配中…' : '根据语义智能分配' }}
                    </button>
                  </div>
                  <div v-if="layoutList.length === 0" class="step1-empty">暂无版式，请检查 template-service 与 examples/layouts.json</div>
                  <div v-else class="layout-per-page">
                    <div
                      v-for="(page, idx) in step3PageContents"
                      :key="idx"
                      class="layout-page-row"
                    >
                      <span class="layout-page-label">第 {{ idx + 1 }} 页 — {{ page.theme }}</span>
                      <select
                        v-model="pageLayoutCodes[idx]"
                        class="layout-select"
                      >
                        <option value="">请选择版式</option>
                        <option
                          v-for="l in layoutList"
                          :key="l.code"
                          :value="l.code"
                        >
                          {{ l.code }} {{ l.name }}
                        </option>
                      </select>
                    </div>
                  </div>
                </template>
                <p v-else class="step1-empty">请先完成「形成每页内容」后再选择版式。</p>
              </div>

              <!-- Step 5: 选择风格（仅从风格管理中已有的风格选择） -->
              <div v-show="currentStep === 4" class="step-content">
                <p v-if="step3StyleError" class="error-msg">{{ step3StyleError }}</p>
                <div v-else-if="step3StyleLoading" class="loading-hint">加载风格列表中…</div>
                <template v-else-if="styleList.length === 0">
                  <p class="step1-empty">暂无风格，请先在「风格管理」中上传 PPT 生成风格后再选择。</p>
                </template>
                <div v-else class="style-step-body">
                  <div class="style-step-top">
                    <p class="step-desc style-step-desc">选择一种风格，仅可从风格管理中已创建的风格中选择。</p>
                    <div class="style-list">
                      <label
                        v-for="s in styleListPageItems"
                        :key="s.id"
                        class="style-option"
                        :class="{ selected: selectedStyleId === s.id }"
                      >
                        <input v-model="selectedStyleId" type="radio" :value="s.id" />
                        <span>{{ s.name }}</span>
                      </label>
                    </div>
                    <div v-if="styleListTotalPages > 1" class="style-pagination">
                      <button
                        type="button"
                        class="btn-ghost style-pagination-btn"
                        :disabled="styleListPage === 0"
                        @click="styleListPage = Math.max(0, styleListPage - 1)"
                      >
                        上一页
                      </button>
                      <span class="style-pagination-info">{{ styleListPage + 1 }} / {{ styleListTotalPages }}</span>
                      <button
                        type="button"
                        class="btn-ghost style-pagination-btn"
                        :disabled="styleListPage >= styleListTotalPages - 1"
                        @click="styleListPage = Math.min(styleListTotalPages - 1, styleListPage + 1)"
                      >
                        下一页
                      </button>
                    </div>
                  </div>
                  <div v-if="selectedStyleId" class="style-preview-wrap">
                    <p class="style-preview-title">风格预览 — {{ styleList.find((s) => s.id === selectedStyleId)?.name }}</p>
                    <div v-if="stylePreviewLoading" class="style-preview-loading">加载预览中…</div>
                    <template v-else-if="stylePreviewDetail">
                      <div class="style-preview-slot">
                        <iframe
                          v-if="stylePreviewCurrentHtml"
                          :srcdoc="stylePreviewCurrentHtml"
                          class="style-preview-iframe"
                          title="风格预览"
                          sandbox="allow-same-origin allow-scripts"
                        />
                        <div v-else class="style-preview-empty">暂无风格背景</div>
                      </div>
                    </template>
                  </div>
                </div>
              </div>

              <!-- Step 6: 逐页生成（风格背景 + 每页文字与图片 → HTML 预览） -->
              <div v-show="currentStep === 5" class="step-content">
                <p class="step-desc">使用选中的风格作为背景，将「形成每页内容」中的文字与图片生成 HTML，可逐页预览。</p>
                <p v-if="step4Error" class="error-msg">{{ step4Error }}</p>
                <div
                  v-else-if="step4Pages.length === 0 && !step4Loading"
                  class="step4-start-row"
                >
                  <button
                    type="button"
                    class="btn-primary"
                    :disabled="step3PageContents.length === 0 || !selectedStyleId"
                    @click="generateStep4Pages()"
                  >
                    开始逐页生成
                  </button>
                  <p v-if="step3PageContents.length === 0 || !selectedStyleId" class="step4-hint">
                    请先完成「形成每页内容」并选择风格后再生成。
                  </p>
                </div>
                <div v-else-if="step4Loading && step4Pages.length === 0" class="loading-hint">正在生成各页 HTML…</div>
                <template v-else-if="step4Pages.length > 0">
                  <div class="pages-preview single-page-preview">
                    <div class="page-card" v-if="step4Pages[step4CurrentPageIndex]">
                      <div class="page-card-head">
                        <span>
                          第 {{ step4Pages[step4CurrentPageIndex]?.id ?? '' }} 页 — {{
                            step3PageContents[step4CurrentPageIndex]?.theme ?? ''
                          }}
                        </span>
                        <button
                          type="button"
                          class="btn-small"
                          :disabled="step4Loading"
                          @click="openStep4RegenerateModal(step4CurrentPageIndex)"
                        >
                          {{
                            step4Pages[step4CurrentPageIndex]?.status === 'generating'
                              ? '生成中…'
                              : '重新生成'
                          }}
                        </button>
                      </div>
                      <div
                        v-if="step4Pages[step4CurrentPageIndex]?.status === 'generating'"
                        class="page-card-loading"
                      >
                        生成中…
                      </div>
                      <iframe
                        v-else-if="step4Pages[step4CurrentPageIndex]?.html"
                        :srcdoc="wrapGeneratedPageHtmlForPreview(step4Pages[step4CurrentPageIndex]?.html || '')"
                        class="page-iframe"
                        :title="`第${step4Pages[step4CurrentPageIndex]?.id ?? ''}页预览`"
                        sandbox="allow-same-origin allow-scripts"
                      />
                    </div>
                    <div class="page-nav-bar">
                      <button
                        type="button"
                        class="btn-small"
                        :disabled="step4CurrentPageIndex <= 0"
                        @click="step4CurrentPageIndex--"
                      >
                        上一页
                      </button>
                      <span class="page-nav-info">
                        第 {{ step4CurrentPageIndex + 1 }} / {{ step4Pages.length }} 页
                      </span>
                      <button
                        type="button"
                        class="btn-small"
                        :disabled="step4CurrentPageIndex >= step4Pages.length - 1"
                        @click="step4CurrentPageIndex++"
                      >
                        下一页
                      </button>
                    </div>
                  </div>
                </template>
                <p v-else class="step1-empty">请先完成「形成每页内容」并选择风格，点击「开始逐页生成」按钮开始。</p>
              </div>

              <!-- Step 7: 制作完成 -->
              <div v-show="currentStep === 6" class="step-content">
                <p class="step-desc">制作完成，可保存到本地记录（暂无后端，仅前端模拟）。</p>
                <div class="summary">
                  <p><strong>主题：</strong>{{ step1Topic || '未命名' }}</p>
                  <p><strong>已选文档：</strong>{{ selectedPdfIds.length }} 个 PDF</p>
                  <p><strong>已选图片：</strong>{{ selectedImageIds.length }} 张</p>
                  <p><strong>版式：</strong>已为 {{ pageLayoutCodes.filter(Boolean).length }} 页分配</p>
                  <p><strong>风格：</strong>{{ styleList.find((s) => s.id === selectedStyleId)?.name || '—' }}</p>
                  <p><strong>页数：</strong>{{ step3PageContents.length || step4Pages.length }} 页</p>
                </div>
                <button type="button" class="btn-primary" @click="saveProject">保存</button>
              </div>
            </div>

            <div class="modal-footer">
              <p v-if="stepSaveError" class="error-msg modal-footer-error">{{ stepSaveError }}</p>
              <div class="modal-footer-buttons">
                <button v-if="currentStep > 0" type="button" class="btn-ghost" @click="goPrev">上一步</button>
                <button
                  v-if="currentStep < 6"
                  type="button"
                  class="btn-primary"
                  :disabled="!canGoNext()"
                  @click="goNext"
                >
                  下一步
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 添加图片弹窗：缩略图+描述，分页 -->
    <Teleport to="body">
      <Transition name="modal">
        <div v-if="addImageModalOpen" class="modal-mask add-image-modal-mask" @click.self="closeAddImageModal">
          <div class="add-image-modal">
            <div class="modal-head">
              <span class="modal-title">为第 {{ addImageModalPageIdx + 1 }} 页添加图片（最多 {{ STEP3_MAX_IMAGES_PER_PAGE }} 张）</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closeAddImageModal">×</button>
            </div>
            <div class="add-image-modal-body">
              <!-- 放大预览层：点击缩略图后显示，可在此添加或返回 -->
              <div v-if="addImageModalPreviewId" class="add-image-preview-layer">
                <div class="add-image-preview-content">
                  <img
                    v-if="pageImageUrlCache[addImageModalPreviewId]"
                    :src="pageImageUrlCache[addImageModalPreviewId]"
                    :alt="imageLabel(addImageModalPreviewId)"
                    class="add-image-preview-img"
                  />
                  <div v-else class="add-image-preview-loading">加载中…</div>
                  <p class="add-image-preview-desc">{{ imageLabel(addImageModalPreviewId) || addImageModalPreviewId }}</p>
                  <div class="add-image-preview-actions">
                    <button type="button" class="btn-primary" @click="addPageImageFromModal(addImageModalPreviewId)">
                      添加此图片
                    </button>
                    <button type="button" class="btn-small" @click="closeAddImagePreview">返回</button>
                  </div>
                </div>
              </div>
              <!-- 缩略图列表：点击为放大，不直接添加 -->
              <template v-else-if="addImageModalCandidateIds.length === 0">
                <p class="add-image-empty">本页已选满或没有更多候选图片。</p>
              </template>
              <template v-else>
                <div class="add-image-grid">
                  <button
                    v-for="imageId in addImageModalCurrentPageIds"
                    :key="imageId"
                    type="button"
                    class="add-image-item"
                    @click="showAddImagePreview(imageId)"
                  >
                    <div class="add-image-thumb">
                      <img
                        v-if="pageImageUrlCache[imageId]"
                        :src="pageImageUrlCache[imageId]"
                        :alt="imageLabel(imageId)"
                        class="add-image-img"
                      />
                      <div v-else class="add-image-loading" />
                    </div>
                    <p class="add-image-desc">{{ imageLabel(imageId) || imageId }}</p>
                  </button>
                </div>
                <div v-if="addImageModalTotalPages > 1" class="add-image-pagination">
                  <button
                    type="button"
                    class="btn-small"
                    :disabled="addImageModalPaginationPage <= 1"
                    @click="addImageModalPaginationPage = addImageModalPaginationPage - 1"
                  >
                    上一页
                  </button>
                  <span class="add-image-page-num">{{ addImageModalPaginationPage }} / {{ addImageModalTotalPages }}</span>
                  <button
                    type="button"
                    class="btn-small"
                    :disabled="addImageModalPaginationPage >= addImageModalTotalPages"
                    @click="addImageModalPaginationPage = addImageModalPaginationPage + 1"
                  >
                    下一页
                  </button>
                </div>
              </template>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 逐页生成：重新生成方式（直接 / 修改意见+上一版） -->
    <Teleport to="body">
      <Transition name="modal">
        <div
          v-if="step4RegenerateModalOpen"
          class="modal-mask add-image-modal-mask"
          @click.self="closeStep4RegenerateModal"
        >
          <div class="add-image-modal step4-regenerate-modal" role="dialog" aria-modal="true">
            <div class="modal-head">
              <span class="modal-title">重新生成本页 HTML</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closeStep4RegenerateModal">
                ×
              </button>
            </div>
            <div class="add-image-modal-body step4-regenerate-body">
              <p class="step4-regenerate-hint">第 {{ step4RegeneratePageIdx + 1 }} 页 — {{ step3PageContents[step4RegeneratePageIdx]?.theme ?? '' }}</p>
              <div class="step4-regenerate-options">
                <label class="step4-regenerate-label">
                  <input v-model="step4RegenerateMode" type="radio" value="direct" />
                  直接重新生成
                </label>
                <label class="step4-regenerate-label">
                  <input v-model="step4RegenerateMode" type="radio" value="revision" />
                  根据修改意见，在上一版基础上重新生成
                </label>
              </div>
              <textarea
                v-show="step4RegenerateMode === 'revision'"
                v-model="step4RegenerateFeedback"
                class="step4-regenerate-textarea"
                rows="5"
                placeholder="例如：标题字号加大、减少留白、把要点改为三列排版…"
              />
              <p v-if="step4RegenerateInlineError" class="error-msg">{{ step4RegenerateInlineError }}</p>
            </div>
            <div class="step4-regenerate-footer">
              <button type="button" class="btn-ghost" @click="closeStep4RegenerateModal">取消</button>
              <button type="button" class="btn-primary" @click="confirmStep4Regenerate">开始生成</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="previewModalOpen" class="modal-mask" @click.self="closePreviewModal">
          <div class="preview-modal" role="dialog" aria-modal="true">
            <div class="modal-head">
              <span class="modal-title">预览：{{ previewTopic || '未命名' }}</span>
              <button type="button" class="modal-close" aria-label="关闭" @click="closePreviewModal">×</button>
            </div>
            <div class="preview-body">
              <div class="preview-toolbar">
                <button type="button" class="btn-small" :disabled="previewLoading || previewPageIndex <= 0" @click="previewPrevPage">
                  上一页
                </button>
                <span class="preview-page-indicator">
                  {{ previewTotalPages ? `${previewCurrentPageNumber}/${previewTotalPages}` : '--/--' }}
                </span>
                <button
                  type="button"
                  class="btn-small"
                  :disabled="previewLoading || previewPageIndex >= previewTotalPages - 1"
                  @click="previewNextPage"
                >
                  下一页
                </button>
              </div>

              <p v-if="previewLoading" class="empty-hint">加载预览中…</p>
              <p v-else-if="previewError" class="empty-hint">{{ previewError }}</p>
              <div v-else class="preview-frame-wrap">
                <iframe
                  class="preview-iframe"
                  :srcdoc="previewCurrentSrcdoc"
                  title="PPT 预览"
                  sandbox="allow-same-origin allow-scripts"
                />
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
  padding: 28px 24px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-background-soft);
  box-shadow: var(--shadow-card);
}

.head-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
}

.title {
  font-size: 1.4rem;
  font-weight: 800;
  color: var(--color-heading);
  margin: 0 0 8px;
}

.desc {
  color: var(--color-text-muted);
  margin: 0;
}

.btn-primary {
  padding: 8px 16px;
  font-size: 0.95rem;
  font-weight: 500;
  color: #fff;
  background: var(--color-primary, #2563eb);
  border: none;
  border-radius: var(--radius);
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  filter: brightness(1.05);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.history-section {
  margin-top: 16px;
}

.section-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-heading);
  margin: 0 0 12px;
}

.history-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
}

.history-title {
  font-weight: 500;
  color: var(--color-heading);
}

.history-date {
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.history-meta {
  font-size: 0.85rem;
  color: var(--color-text-muted);
  margin-left: 8px;
}

.history-item-with-action {
  gap: 12px;
}

.history-item-with-action .history-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-hint {
  color: var(--color-text-muted);
  margin: 0;
}

/* Modal */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
}

.workshop-modal {
  /* 放大制作工坊弹窗，给逐页预览更多空间 */
  width: 98vw;
  max-width: 1400px;
  height: 97vh;
  max-height: 97vh;
  min-height: 820px;
  background: var(--color-background);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
}

.preview-modal {
  width: 98vw;
  max-width: 1400px;
  height: 94vh;
  max-height: 94vh;
  min-height: 640px;
  background: var(--color-background);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
}

.preview-body {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px 16px 16px;
  gap: 12px;
}

.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.preview-page-indicator {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  min-width: 72px;
  text-align: center;
}

.preview-frame-wrap {
  flex: 1 1 0;
  min-height: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  overflow: hidden;
  background: #fff;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
}

.modal-title {
  font-size: 1.15rem;
  font-weight: 600;
  color: var(--color-heading);
}

.modal-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  font-size: 1.5rem;
  line-height: 1;
  color: var(--color-text-muted);
  cursor: pointer;
  border-radius: var(--radius);
}

.modal-close:hover {
  color: var(--color-heading);
  background: var(--color-background-mute);
}

.ppt-resume-section {
  padding: 12px 20px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-background-mute, #f5f5f5);
}

.ppt-resume-title {
  margin: 0 0 8px;
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--color-text-muted);
}

.ppt-resume-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ppt-resume-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background);
}

.ppt-resume-topic {
  flex: 1;
  font-weight: 500;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ppt-resume-meta {
  flex-shrink: 0;
  font-size: 0.8rem;
  color: var(--color-text-muted);
}

.steps-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  padding: 12px 20px;
  background: var(--color-background-mute);
  border-bottom: 1px solid var(--color-border);
}

.step-node {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.85rem;
  color: var(--color-text-muted);
}

.step-node .step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--color-border);
  color: var(--color-text-muted);
}

.step-node.active .step-num,
.step-node.done .step-num {
  background: var(--color-primary, #2563eb);
  color: #fff;
}

.step-node.active {
  color: var(--color-heading);
  font-weight: 600;
}

.step-arrow {
  margin-left: 4px;
  color: var(--color-border);
}

.modal-body {
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
  padding: 20px;
  /* 让步骤内容区纵向拉伸，占满弹窗剩余高度，便于预览区域最大化 */
  display: flex;
  flex-direction: column;
}

.step-content {
  min-height: 0; /* 允许在 flex 中收缩，以便内部可滚动区域生效 */
  /* 当前步骤内容区占满 modal-body 的剩余高度 */
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
}

.step-desc {
  color: var(--color-text-muted);
  margin: 0 0 8px;
  font-size: 0.95rem;
}

.step1-hint {
  color: var(--color-text-muted);
  margin: 0 0 16px;
}
.step1-block {
  margin-bottom: 20px;
}
.step1-subtitle {
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--color-heading);
  margin: 0 0 8px;
}
.step1-dropdown-row {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}
.step1-select {
  flex: 1;
  min-width: 0;
  max-width: 420px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.95rem;
  background: var(--color-background);
}
.btn-add {
  flex-shrink: 0;
  padding: 8px 16px;
  font-size: 0.9rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background-mute);
  cursor: pointer;
}
.btn-add:hover:not(:disabled) {
  background: var(--color-primary, #2563eb);
  color: #fff;
  border-color: var(--color-primary, #2563eb);
}
.btn-add:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.step1-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-background-mute);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.9rem;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chip-remove {
  flex-shrink: 0;
  padding: 0 2px;
  font-size: 1.1rem;
  line-height: 1;
  color: var(--color-text-muted);
  background: none;
  border: none;
  cursor: pointer;
}
.chip-remove:hover {
  color: var(--color-danger, #dc2626);
}
.step1-hint-inline {
  color: var(--color-text-muted);
  font-size: 0.85rem;
  margin: 4px 0 8px;
}
.step1-empty {
  color: var(--color-text-muted);
  font-size: 0.9rem;
  margin: 0;
}

.topic-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topic-row label {
  flex-shrink: 0;
}

.topic-input {
  flex: 1;
  max-width: 400px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font-size: 0.95rem;
}

.outline-actions {
  margin-bottom: 12px;
}

.error-msg {
  color: var(--color-danger, #dc2626);
  font-size: 0.9rem;
  margin: 0 0 8px;
}

.page-contents-preview {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 12px;
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
}

.page-content-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 14px 16px;
  background: var(--color-background-soft, #f8f9fa);
}

.page-content-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.page-content-theme {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-heading);
  flex: 1;
  min-width: 0;
}

.page-content-loading-hint {
  color: var(--color-text-muted);
  font-size: 0.9rem;
  padding: 8px 0;
}

.page-content-text {
  white-space: pre-wrap;
  font-size: 0.9rem;
  line-height: 1.5;
  color: var(--color-text);
  margin-bottom: 10px;
}

.page-content-text-edit {
  width: 100%;
  min-height: 120px;
  resize: vertical;
  font-family: inherit;
  font-size: 0.9rem;
  line-height: 1.5;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  color: var(--color-text);
  background: var(--color-background);
  margin-bottom: 10px;
}

.page-content-images-row {
  margin-top: 8px;
}

.page-content-images {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px;
}

.page-content-img-wrap {
  position: relative;
  width: 120px;
  height: 90px;
  border-radius: 4px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--color-background-mute);
  display: flex;
  align-items: center;
  justify-content: center;
}

.page-content-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.page-content-img-loading {
  width: 100%;
  height: 100%;
  background: var(--color-background-mute);
  min-width: 120px;
  min-height: 90px;
}

.page-content-img-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.page-content-img-remove:hover {
  background: rgba(0, 0, 0, 0.85);
}

.page-content-add-image-btn {
  width: 120px;
  height: 90px;
  border: 1px dashed var(--color-border);
  border-radius: 4px;
  background: var(--color-background);
  font-size: 0.9rem;
  color: var(--color-text);
  cursor: pointer;
}
.page-content-add-image-btn:hover {
  border-color: var(--color-primary, #2563eb);
  color: var(--color-primary, #2563eb);
}

.add-image-modal-mask {
  z-index: 10001;
}
.add-image-modal {
  position: relative;
  width: 90%;
  max-width: 720px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  background: var(--color-background);
  border-radius: var(--radius);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}
.add-image-modal .modal-head {
  flex-shrink: 0;
}
.add-image-modal-body {
  padding: 16px 20px 20px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}
.step4-regenerate-modal {
  max-width: 520px;
}
.step4-regenerate-hint {
  font-size: 0.9rem;
  color: var(--color-text-muted);
  margin: 0 0 12px;
}
.step4-regenerate-options {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.step4-regenerate-label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 0.9rem;
  line-height: 1.45;
  cursor: pointer;
}
.step4-regenerate-label input {
  margin-top: 3px;
  flex-shrink: 0;
}
.step4-regenerate-textarea {
  width: 100%;
  margin-top: 12px;
  padding: 10px 12px;
  font-family: inherit;
  font-size: 0.9rem;
  line-height: 1.5;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  resize: vertical;
  min-height: 100px;
  color: var(--color-text);
  background: var(--color-background);
  box-sizing: border-box;
}
.step4-regenerate-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 20px 18px;
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}
.add-image-empty {
  color: var(--color-text-muted);
  text-align: center;
  padding: 24px;
}

.add-image-preview-layer {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}
.add-image-preview-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  max-width: 100%;
}
.add-image-preview-img {
  max-width: 100%;
  max-height: 60vh;
  object-fit: contain;
  border-radius: 8px;
  border: 1px solid var(--color-border);
}
.add-image-preview-loading {
  width: 200px;
  height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-background-mute);
  border-radius: 8px;
  color: var(--color-text-muted);
}
.add-image-preview-desc {
  margin: 12px 0 16px;
  font-size: 0.9rem;
  color: var(--color-text);
  text-align: center;
  max-width: 100%;
  overflow-wrap: break-word;
}
.add-image-preview-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}

.add-image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}
.add-image-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--color-background-soft, #f8f9fa);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.add-image-item:hover {
  border-color: var(--color-primary, #2563eb);
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.2);
}
.add-image-thumb {
  width: 100%;
  aspect-ratio: 4/3;
  background: var(--color-background-mute);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
.add-image-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.add-image-loading {
  width: 100%;
  height: 100%;
  min-height: 80px;
  background: var(--color-background-mute);
}
.add-image-desc {
  width: 100%;
  margin: 0;
  padding: 8px;
  font-size: 0.8rem;
  line-height: 1.3;
  color: var(--color-text);
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.add-image-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}
.add-image-page-num {
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.page-content-img-placeholder {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  padding: 4px;
  text-align: center;
}

.outline-mode-toggle {
  margin-top: 12px;
}

.outline-edit-wrap {
  margin-bottom: 0;
}

.outline-edit-label {
  display: block;
  font-size: 0.9rem;
  font-weight: 500;
  color: var(--color-heading);
  margin-bottom: 6px;
}

.outline-textarea {
  width: 100%;
  min-height: 220px;
  padding: 12px 14px;
  font-size: 0.9rem;
  line-height: 1.5;
  font-family: inherit;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  resize: vertical;
  box-sizing: border-box;
}

.outline-textarea:focus {
  outline: none;
  border-color: var(--color-primary, #2563eb);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.15);
}

.outline-preview-label {
  font-size: 0.85rem;
  color: var(--color-text-muted);
  margin-bottom: 6px;
  display: block;
}

.outline-preview-wrap {
  width: 100%;
  height: 320px;
  margin-top: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  overflow: auto;
  box-sizing: border-box;
}

/* 编辑模式与预览模式同高，内容超出用滚轮滚动 */
.outline-edit-fixed {
  height: 320px;
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.outline-edit-fixed .outline-textarea {
  flex: 1;
  min-height: 0;
  overflow: auto;
  resize: none;
}

.outline-preview {
  padding: 12px 16px;
  font-size: 0.9rem;
  line-height: 1.5;
}

.outline-preview.markdown-body :deep(h2) { font-size: 1.1rem; margin: 1em 0 0.5em; color: var(--color-text); }
.outline-preview.markdown-body :deep(h3) { font-size: 1rem; margin: 0.8em 0 0.4em; color: var(--color-text); }
.outline-preview.markdown-body :deep(ul) { margin: 0.4em 0; padding-left: 1.5em; }
.outline-preview.markdown-body :deep(li) { margin: 0.2em 0; }
.outline-preview.markdown-body :deep(p) { margin: 0.5em 0; }

.outline-placeholder {
  color: var(--color-text-muted);
}

.layout-actions {
  margin-bottom: 16px;
}

.layout-per-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
}

.layout-page-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background-soft, #f8f9fa);
}

.layout-page-label {
  flex: 1;
  font-size: 0.9rem;
  color: var(--color-text);
  min-width: 0;
}

.layout-select {
  flex-shrink: 0;
  min-width: 200px;
  padding: 6px 10px;
  font-size: 0.9rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background);
}

.loading-hint {
  color: var(--color-text-muted);
  margin-bottom: 12px;
}

/* 风格选择上半部分：固定高度，无论风格数量多少都保持相同面积，列表过多时内部滚动 */
.style-step-top {
  height: 260px;
  min-height: 260px;
  max-height: 260px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background-soft, #f8f9fa);
  box-sizing: border-box;
}

.style-step-top .style-step-desc {
  margin: 0 0 2px;
  flex-shrink: 0;
}

.style-step-top .style-pagination {
  flex-shrink: 0;
  margin-top: 0;
}

.style-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
}

.style-option {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  cursor: pointer;
}

.style-option.selected {
  border-color: var(--color-primary, #2563eb);
  background: rgba(37, 99, 235, 0.06);
}

.style-pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.style-pagination-btn {
  padding: 6px 12px;
  font-size: 0.9rem;
}

.style-pagination-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.style-pagination-info {
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.style-step-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.style-preview-wrap {
  margin-top: 8px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-background-soft, #f8f9fa);
}

.style-preview-title {
  margin: 0 0 10px;
  font-size: 0.95rem;
  font-weight: 600;
  color: var(--color-heading);
}

.style-preview-loading {
  padding: 24px;
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

/* 选择风格仅展示一页预览，不区分标题页/内容页/结尾页 */
.style-preview-slot {
  min-height: 280px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  overflow: hidden;
  background: #fff;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
}

.style-preview-iframe {
  width: 100%;
  height: 380px;
  border: none;
  display: block;
}

.style-preview-empty {
  min-height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.start-gen {
  padding: 24px 0;
}

.pages-preview {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.pages-preview.single-page-preview {
  /* 在 Step6 中占据当前步骤内容区的剩余高度 */
  flex: 1 1 0;
}

.page-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  /* 允许预览内容在需要时稍微溢出卡片，而不是被裁剪 */
  overflow: hidden;
  flex: 1 1 auto;
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--color-background-mute);
  font-size: 0.9rem;
  font-weight: 500;
}

.btn-small {
  padding: 4px 10px;
  font-size: 0.85rem;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: transparent;
  cursor: pointer;
}
.btn-small.danger {
  color: var(--vt-c-red);
  border-color: rgba(220, 38, 38, 0.35);
}
.btn-small:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.page-content-card-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-placeholder {
  padding: 40px;
  text-align: center;
  color: var(--color-text-muted);
}

.page-card-loading {
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-background-mute);
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.page-iframe {
  width: 100%;
  height: 100%;
  min-height: 0;
  border: none;
  background: #fff;
}

.page-nav-bar {
  flex: 0 0 auto;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.page-nav-info {
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.step4-start-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.step4-hint {
  margin: 0;
  font-size: 0.9rem;
  color: var(--color-text-muted);
}

.summary {
  margin-bottom: 20px;
}

.summary p {
  margin: 0 0 8px;
}

.modal-footer {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  padding: 16px 20px;
  border-top: 1px solid var(--color-border);
}

.modal-footer-error {
  width: 100%;
  margin: 0;
}

.modal-footer-buttons {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-ghost {
  padding: 8px 16px;
  font-size: 0.95rem;
  color: var(--color-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--radius);
  cursor: pointer;
}

.btn-ghost:hover {
  color: var(--color-heading);
  background: var(--color-background-mute);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
