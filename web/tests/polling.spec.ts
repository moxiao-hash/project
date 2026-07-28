import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePolling } from '@/composables/usePolling'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'

function mountPolling<T>(options: Parameters<typeof usePolling<T>>[0]) {
  let api!: ReturnType<typeof usePolling<T>>
  const wrapper = mount(
    defineComponent({
      setup() {
        api = usePolling<T>(options)
        return () => h('div')
      },
    }),
  )
  return { wrapper, get api() { return api } }
}

describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('非终止状态持续轮询，进入终止状态后停止', async () => {
    const states = ['PROCESSING', 'PROCESSING', 'READY']
    const fetcher = vi.fn(() => Promise.resolve(states.shift() ?? 'READY'))
    const { api } = mountPolling<string>({
      fetcher,
      shouldContinue: (s) => s !== 'READY',
      interval: 1000,
    })

    api.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(api.polling.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1000)
    expect(fetcher).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(1000)
    expect(fetcher).toHaveBeenCalledTimes(3)
    // READY 是终止状态，应停止
    expect(api.polling.value).toBe(false)

    await vi.advanceTimersByTimeAsync(5000)
    expect(fetcher).toHaveBeenCalledTimes(3)
  })

  it('连续错误达到上限后停止并触发 onFailed', async () => {
    const fetcher = vi.fn(() => Promise.reject(new Error('boom')))
    const onFailed = vi.fn()
    const { api } = mountPolling<string>({
      fetcher,
      shouldContinue: () => true,
      interval: 100,
      maxConsecutiveErrors: 3,
      onFailed,
    })

    api.start()
    await vi.advanceTimersByTimeAsync(0) // 第 1 次失败，退避到 200ms
    await vi.advanceTimersByTimeAsync(200) // 第 2 次失败，退避到 400ms
    await vi.advanceTimersByTimeAsync(400) // 第 3 次失败，达到上限

    expect(onFailed).toHaveBeenCalledOnce()
    expect(api.polling.value).toBe(false)
  })

  it('组件卸载后不再发起请求', async () => {
    const fetcher = vi.fn(() => Promise.resolve('PENDING'))
    const { api, wrapper } = mountPolling<string>({
      fetcher,
      shouldContinue: () => true,
      interval: 1000,
    })

    api.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await nextTick()
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })
})
