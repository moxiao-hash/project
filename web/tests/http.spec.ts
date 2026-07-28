import { describe, expect, it, vi, beforeEach } from 'vitest'
import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { http, setUnauthorizedHandler, TOKEN_STORAGE_KEY, describeError } from '@/services/http'

function fakeConfig(): InternalAxiosRequestConfig {
  return { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
}

describe('http 拦截器', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('请求拦截器自动附带 sessionStorage 中的 Bearer token', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'token-xyz')
    const handler = (http.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: InternalAxiosRequestConfig) => InternalAxiosRequestConfig }>
    }).handlers[0]
    const config = handler.fulfilled(fakeConfig())
    expect(config.headers.Authorization).toBe('Bearer token-xyz')
  })

  it('无 token 时不设置 Authorization 头', () => {
    const handler = (http.interceptors.request as unknown as {
      handlers: Array<{ fulfilled: (c: InternalAxiosRequestConfig) => InternalAxiosRequestConfig }>
    }).handlers[0]
    const config = handler.fulfilled(fakeConfig())
    expect(config.headers.Authorization).toBeUndefined()
  })

  it('401 响应清除 token 并触发统一登出回调', async () => {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, 'expired')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)

    const rejected = (http.interceptors.response as unknown as {
      handlers: Array<{ rejected: (e: unknown) => Promise<never> }>
    }).handlers[0].rejected

    const error = new AxiosError('unauthorized', '401', fakeConfig(), {}, {
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config: fakeConfig(),
      data: { message: 'token expired' },
    })

    await expect(rejected(error)).rejects.toThrow()
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledOnce()
    setUnauthorizedHandler(null)
  })

  it('describeError 优先展示 fieldErrors', () => {
    const error = new AxiosError('bad request', '400', fakeConfig(), {}, {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: fakeConfig(),
      data: {
        timestamp: '',
        status: 400,
        error: 'Bad Request',
        message: '校验失败',
        path: '/api/learning-goals',
        fieldErrors: { title: '标题不能为空' },
      },
    })
    expect(describeError(error)).toBe('标题不能为空')
  })

  it('describeError 对 409 给出刷新提示', () => {
    const error = new AxiosError('conflict', '409', fakeConfig(), {}, {
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config: fakeConfig(),
      data: null,
    })
    expect(describeError(error)).toContain('已被修改')
  })
})
