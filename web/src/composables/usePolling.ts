import { onBeforeUnmount, onMounted, ref } from 'vue'

export interface PollingOptions<T> {
  /** 拉取函数，返回最新数据 */
  fetcher: () => Promise<T>
  /** 返回 true 时继续轮询；false 时正常停止 */
  shouldContinue: (data: T) => boolean
  /** 基础间隔（毫秒），浏览器后台时自动降为 3 倍 */
  interval?: number
  /** 连续错误达到该次数后停止并触发 onFailed */
  maxConsecutiveErrors?: number
  onData?: (data: T) => void
  onFailed?: (error: unknown) => void
  /** 页面隐藏时是否继续（默认 true，但降频） */
  pauseWhenHidden?: boolean
}

/**
 * 页面级轮询：退避错误、后台降频、组件卸载自动清理。
 * 用于资料解析状态、异步评分、Agent 执行等场景。
 */
export function usePolling<T>(options: PollingOptions<T>) {
  const polling = ref(false)
  const consecutiveErrors = ref(0)
  let timer: ReturnType<typeof setTimeout> | null = null
  let stopped = true

  const interval = options.interval ?? 3000
  const maxErrors = options.maxConsecutiveErrors ?? 5

  function effectiveInterval(): number {
    let ms = interval
    if (typeof document !== 'undefined' && document.hidden) ms *= 3
    // 错误退避：1x, 2x, 4x ...
    ms *= 2 ** consecutiveErrors.value
    return ms
  }

  async function tick() {
    if (stopped) return
    try {
      const data = await options.fetcher()
      consecutiveErrors.value = 0
      options.onData?.(data)
      if (stopped) return
      if (!options.shouldContinue(data)) {
        stop()
        return
      }
    } catch (error) {
      consecutiveErrors.value += 1
      if (consecutiveErrors.value >= maxErrors) {
        stop()
        options.onFailed?.(error)
        return
      }
    }
    if (!stopped) timer = setTimeout(tick, effectiveInterval())
  }

  function start() {
    if (!stopped) return
    stopped = false
    polling.value = true
    consecutiveErrors.value = 0
    void tick()
  }

  function stop() {
    stopped = true
    polling.value = false
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  onMounted(() => {
    // 页面重新可见时立即补一次，避免用户等待降频间隔
    document.addEventListener('visibilitychange', onVisible)
  })
  function onVisible() {
    if (!document.hidden && !stopped) {
      if (timer !== null) clearTimeout(timer)
      timer = setTimeout(tick, 200)
    }
  }

  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { polling, consecutiveErrors, start, stop }
}
