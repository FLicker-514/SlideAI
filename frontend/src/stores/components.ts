import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export interface CustomComponentItem {
  id: string
  name: string
  description: string
  html: string
  createdAt: string
  updatedAt?: string
}

const STORAGE_KEY = 'ppt_component_library_v1'

function safeParseList(raw: string | null): CustomComponentItem[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((x): x is CustomComponentItem => !!x && typeof x === 'object')
      .map((x) => x as CustomComponentItem)
      .filter((x) => typeof x.id === 'string' && typeof x.name === 'string' && typeof x.html === 'string')
  } catch {
    return []
  }
}

function persist(list: CustomComponentItem[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(list))
}

export const useComponentsStore = defineStore('components', () => {
  const items = ref<CustomComponentItem[]>(safeParseList(localStorage.getItem(STORAGE_KEY)))

  const count = computed(() => items.value.length)

  function reload() {
    items.value = safeParseList(localStorage.getItem(STORAGE_KEY))
  }

  function upsert(input: Omit<CustomComponentItem, 'createdAt'> & { createdAt?: string }) {
    const now = new Date().toISOString()
    const id = input.id
    const idx = items.value.findIndex((x) => x.id === id)
    const item: CustomComponentItem = {
      id,
      name: input.name,
      description: input.description,
      html: input.html,
      createdAt: input.createdAt || now,
      updatedAt: now
    }
    if (idx >= 0) items.value.splice(idx, 1, item)
    else items.value.unshift(item)
    persist(items.value)
  }

  function remove(id: string) {
    items.value = items.value.filter((x) => x.id !== id)
    persist(items.value)
  }

  function clearAll() {
    items.value = []
    localStorage.removeItem(STORAGE_KEY)
  }

  return { items, count, reload, upsert, remove, clearAll }
})

