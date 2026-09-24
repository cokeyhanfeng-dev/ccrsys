import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get } from '@/api/request'
import { readToken } from '@/auth/storage.mjs'

export const useNavigationStore = defineStore('navigation', () => {
  const rows = ref<any[]>([])
  let owner = ''
  let pending: Promise<void> | null = null
  let loadedAt = 0
  async function load(force = false) {
    const token = readToken()
    if (!token) { rows.value = []; owner = ''; loadedAt = 0; return }
    if (owner !== token) { rows.value = []; loadedAt = 0; pending = null; owner = token }
    if (pending) return pending
    if (!force && Date.now() - loadedAt < 15000) return
    pending = get<any[]>('/auth/menus').then(data => {
      if (owner === token) { rows.value = data; loadedAt = Date.now() }
    }).finally(() => { if (owner === token) pending = null })
    return pending
  }
  return { rows, load }
})
