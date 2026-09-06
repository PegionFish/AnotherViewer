import { describe, expect, it, vi, beforeEach, afterEach, type MockInstance } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import type { Router } from 'vue-router'
import client, {
  EhUnavailableError,
  isEhUnavailableError,
  isOfflineError,
  isOfflinePayload,
  OfflineError,
} from '@/api/client'
import router from '@/router'

describe('isOfflinePayload', () => {
  it('recognizes the SW offline marker as an object payload', () => {
    expect(isOfflinePayload(503, { error: 'offline', message: 'No cached data available' })).toBe(true)
  })

  it('recognizes the marker when the payload arrives as a string', () => {
    expect(isOfflinePayload(503, '{"error":"offline"}')).toBe(true)
  })

  it('rejects other status codes', () => {
    expect(isOfflinePayload(500, { error: 'offline' })).toBe(false)
    expect(isOfflinePayload(200, { error: 'offline' })).toBe(false)
  })

  it('rejects 503 without the offline marker', () => {
    expect(isOfflinePayload(503, { error: 'boom' })).toBe(false)
    expect(isOfflinePayload(503, 'server error')).toBe(false)
    expect(isOfflinePayload(503, undefined)).toBe(false)
  })
})

describe('OfflineError', () => {
  it('is detected by the isOfflineError type guard', () => {
    expect(isOfflineError(new OfflineError())).toBe(true)
    expect(isOfflineError(new Error('offline'))).toBe(false)
    expect(isOfflineError(null)).toBe(false)
  })
})

describe('EhUnavailableError — EH 熔断（plan-2026-08-30 §3.2/§4.1）', () => {
  it('carries the default local-only message when constructed bare', () => {
    expect(new EhUnavailableError().message).toBe('EH 平台当前不可达，仅显示本地内容')
  })

  it('is detected by the isEhUnavailableError type guard', () => {
    expect(isEhUnavailableError(new EhUnavailableError('x'))).toBe(true)
    expect(isEhUnavailableError(new Error('x'))).toBe(false)
    expect(isEhUnavailableError(null)).toBe(false)
  })

  it('recognizes the raw axios-shaped 404 envelope (interceptor wrapper form)', () => {
    const axiosError = {
      response: {
        status: 404,
        data: { error: { code: 'EH_UNAVAILABLE', message: 'EH 平台当前不可达，仅显示本地内容' } },
      },
    }
    expect(isEhUnavailableError(axiosError)).toBe(true)
    const other = { response: { status: 404, data: { error: { code: 'NOT_FOUND' } } } }
    expect(isEhUnavailableError(other)).toBe(false)
    const noEnvelope = { response: { status: 404, data: 'plain text' } }
    expect(isEhUnavailableError(noEnvelope)).toBe(false)
  })
})

/** Adapter rejecting with a bare axios-shaped error (the interceptor's input). */
type RejectingAdapter = (config: InternalAxiosRequestConfig) => Promise<unknown>

function setAdapter(adapter: RejectingAdapter): void {
  client.defaults.adapter = adapter as unknown as NonNullable<typeof client.defaults.adapter>
}

function rejectingAdapter(status: number): RejectingAdapter {
  return (config) =>
    Promise.reject({
      isAxiosError: true,
      config,
      response: { status, data: {}, statusText: `HTTP ${status}`, headers: {}, config },
    })
}

describe('401 拦截器 — 会话失效收口（audit P2）', () => {
  const originalAdapter = client.defaults.adapter
  let replaceSpy: MockInstance<Router['replace']>

  beforeEach(() => {
    localStorage.setItem('token', 'stale-token')
    localStorage.setItem('username', 'bob')
    replaceSpy = vi.spyOn(router, 'replace').mockResolvedValue(undefined)
  })

  afterEach(() => {
    client.defaults.adapter = originalAdapter
    replaceSpy.mockRestore()
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  })

  /** Let the lazy router import + the navigation promise settle. */
  async function settleNavigation(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
  }

  it('clears credentials and routes to Login with a redirect query (SPA, no hard reload)', async () => {
    setAdapter(rejectingAdapter(401))

    await expect(client.get('/galleries')).rejects.toMatchObject({ response: { status: 401 } })
    await settleNavigation()

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('username')).toBeNull()
    expect(replaceSpy).toHaveBeenCalledTimes(1)
    expect(replaceSpy).toHaveBeenCalledWith({ name: 'Login', query: { redirect: '/' } })
  })

  it('collapses a burst of concurrent 401s into a single redirect', async () => {
    setAdapter(rejectingAdapter(401))

    const first = client.get('/a').catch((error) => error)
    const second = client.get('/b').catch((error) => error)
    await Promise.all([first, second])
    await settleNavigation()

    expect(replaceSpy).toHaveBeenCalledTimes(1)
  })

  it('sweeps other in-flight requests by aborting them (session is dead)', async () => {
    setAdapter((config) => {
      if (String(config.url).includes('/slow')) {
        return new Promise((_resolve, reject) => {
          // The 401 sweep aborts the tracked controller; axios surfaces the
          // aborted request as CanceledError (ERR_CANCELED).
          const signal = config.signal as AbortSignal | undefined
          signal?.addEventListener('abort', () => reject(new Error('aborted by 401 sweep')))
        })
      }
      return Promise.reject({
        isAxiosError: true,
        config,
        response: { status: 401, data: {}, statusText: 'HTTP 401', headers: {}, config },
      })
    })

    // /slow 挂在途 → 另一个请求 401 → 在途请求被 abort。
    const slow = client.get('/api/slow').catch((error) => error)
    await Promise.resolve()
    await Promise.resolve()
    await client.get('/api/trigger-401').catch((error) => error)

    // axios 对 signal.abort 的响应是 CanceledError（code ERR_CANCELED）。
    const slowError = (await slow) as { code?: string; message?: string }
    expect(slowError.code).toBe('ERR_CANCELED')
  })

  it('leaves non-401 failures alone (no redirect, credentials kept)', async () => {
    setAdapter(rejectingAdapter(500))

    await expect(client.get('/x')).rejects.toMatchObject({ response: { status: 500 } })
    await settleNavigation()

    expect(replaceSpy).not.toHaveBeenCalled()
    expect(localStorage.getItem('token')).toBe('stale-token')
    expect(localStorage.getItem('username')).toBe('bob')
  })
})
