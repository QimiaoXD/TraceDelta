import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { loginApi } from '../api/auth'

const TOKEN_KEY = 'tracedelta_token'
const USER_KEY = 'tracedelta_username'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const username = ref<string>(localStorage.getItem(USER_KEY) || '')

  const isLoggedIn = computed(() => Boolean(token.value))

  async function login(user: string, password: string) {
    const res = await loginApi(user, password)
    token.value = res.token
    username.value = res.username
    localStorage.setItem(TOKEN_KEY, res.token)
    localStorage.setItem(USER_KEY, res.username)
  }

  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, username, isLoggedIn, login, logout }
})
