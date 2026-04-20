import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User } from '@/api/account'
import { accountApi } from '@/api/account'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const user = ref<User | null>(null)

  const saved = localStorage.getItem('user')
  if (saved) {
    try {
      user.value = JSON.parse(saved) as User
    } catch {
      user.value = null
    }
  }

  const isLoggedIn = computed(() => !!token.value)

  function setLogin(t: string, u: User) {
    token.value = t
    user.value = u
    localStorage.setItem('token', t)
    localStorage.setItem('user', JSON.stringify(u))
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  async function fetchUser() {
    const res = await accountApi.getUserInfo()
    if (res.data.code === 200 && res.data.data) {
      user.value = res.data.data
      localStorage.setItem('user', JSON.stringify(res.data.data))
      return res.data.data
    }
    return null
  }

  return { token, user, isLoggedIn, setLogin, logout, fetchUser }
})
