import axios from 'axios'
import { ElMessage } from 'element-plus'

export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
}

const TOKEN_KEY = 'tracedelta_token'

const http = axios.create({
  baseURL: '/api',
  timeout: 35000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body && body.success === false) {
      const msg = body.message || '请求失败'
      if (!response.config.url?.includes('/auth/login')) {
        ElMessage.error(msg)
      }
      return Promise.reject(new Error(msg))
    }
    return response
  },
  (error) => {
    const status = error?.response?.status
    const isLoginReq = Boolean(error?.config?.url?.includes('/auth/login'))
    const msg = error?.response?.data?.message || error.message || '网络请求异常'
    if (status === 401 && !isLoginReq) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('tracedelta_username')
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    if (!isLoginReq) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

export default http
