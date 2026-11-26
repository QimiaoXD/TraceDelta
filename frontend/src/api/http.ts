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
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return response
  },
  (error) => {
    const status = error?.response?.status
    const msg = error?.response?.data?.message || error.message || '网络请求异常'
    if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem('tracedelta_username')
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default http
