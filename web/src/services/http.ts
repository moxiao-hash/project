import axios, { AxiosError } from 'axios'
import type { ApiError } from '@/types/api'

export const TOKEN_STORAGE_KEY = 'studypilot.accessToken'
export const TOKEN_EXPIRES_KEY = 'studypilot.accessTokenExpiresAt'

/**
 * 浏览器唯一允许的出口：Java 的 /api/**。
 * 禁止在这里配置 Python 地址、内部令牌或任何模型 Key。
 */
export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 45_000,
})

http.interceptors.request.use((config) => {
  const token = sessionStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

/** 401 时由 auth store 注册的回调负责清理会话并跳转登录页。 */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler
}

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      sessionStorage.removeItem(TOKEN_STORAGE_KEY)
      sessionStorage.removeItem(TOKEN_EXPIRES_KEY)
      onUnauthorized?.()
    }
    return Promise.reject(error)
  },
)

export function getApiError(error: unknown): ApiError | null {
  if (error instanceof AxiosError && error.response?.data) {
    const data = error.response.data as Partial<ApiError>
    if (typeof data.message === 'string') return data as ApiError
  }
  return null
}

/** 把异常转换为可展示的中文文案，不暴露内部细节。 */
export function describeError(error: unknown): string {
  if (error instanceof AxiosError) {
    const apiError = getApiError(error)
    const status = error.response?.status
    if (apiError) {
      if (apiError.fieldErrors && Object.keys(apiError.fieldErrors).length > 0) {
        return Object.values(apiError.fieldErrors).join('；')
      }
      return apiError.message
    }
    if (status === 401) return '登录已过期，请重新登录'
    if (status === 404) return '资源不存在或不属于当前账号'
    if (status === 409) return '数据已被修改，请刷新后重试'
    if (status === 422) return '输入内容暂无法由 AI 处理，请调整后重试'
    if (status === 502) return 'AI 输出异常，请稍后重试'
    if (status === 503) return '服务暂不可用，请稍后重试'
    if (error.code === 'ECONNABORTED') return '请求超时，请检查网络后重试'
    return '网络异常，请稍后重试'
  }
  return '发生未知错误'
}
