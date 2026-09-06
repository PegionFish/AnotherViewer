/**
 * 路由守卫 /auth/status 模块级缓存（audit P2）。
 *
 * 免认证部署的匿名会话此前每次导航都发一次 /auth/status；现在 TTL（60s）
 * 内复用缓存，登录/登出必经的 Login 路由入口负责失效缓存。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import router, { invalidateAuthStatusCache } from '@/router'
import { authApi } from '@/api/auth'
import type { AuthStatusResult } from '@/api/auth'

vi.mock('@/api/auth', () => ({
  authApi: { status: vi.fn() },
}))

function statusResult(authRequired: boolean): AuthStatusResult {
  return { authRequired, authenticated: !authRequired } as AuthStatusResult
}

describe('路由守卫 — /auth/status 模块级缓存 (audit P2)', () => {
  beforeEach(async () => {
    localStorage.clear()
    invalidateAuthStatusCache()
    vi.mocked(authApi.status).mockReset()
    // 归位到 Login（守卫直接放行、不触发探测）；已在该路由时 vue-router
    // 视为重复导航直接返回——缓存已由上方 invalidate 清空，等效。
    await router.replace('/login')
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('anonymous session reuses one /auth/status probe across navigations within the TTL', async () => {
    vi.mocked(authApi.status).mockResolvedValue(statusResult(false))

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('Home')
    await router.push('/history')
    expect(router.currentRoute.value.name).toBe('History')
    await router.push('/downloads')
    expect(router.currentRoute.value.name).toBe('Downloads')

    // 三次导航只探测一次（TTL 内命中缓存）。
    expect(authApi.status).toHaveBeenCalledTimes(1)
  })

  it('re-probes after the 60s TTL expires', async () => {
    vi.mocked(authApi.status).mockResolvedValue(statusResult(false))
    await router.push('/')
    expect(authApi.status).toHaveBeenCalledTimes(1)

    vi.useFakeTimers()
    await vi.advanceTimersByTimeAsync(61_000)
    await router.push('/history')

    expect(router.currentRoute.value.name).toBe('History')
    expect(authApi.status).toHaveBeenCalledTimes(2)
  })

  it('a cached authRequired answer keeps redirecting anonymous users to Login', async () => {
    vi.mocked(authApi.status).mockResolvedValue(statusResult(true))

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('Login')
    expect(authApi.status).toHaveBeenCalledTimes(1)

    // 缓存命中：第二跳不再探测，仍然拦回登录页。
    await router.push('/history')
    expect(router.currentRoute.value.name).toBe('Login')
    expect(authApi.status).toHaveBeenCalledTimes(1)
  })

  it('a token short-circuits the probe entirely', async () => {
    localStorage.setItem('token', 'tok')
    vi.mocked(authApi.status).mockResolvedValue(statusResult(true))

    await router.push('/')
    expect(router.currentRoute.value.name).toBe('Home')
    expect(authApi.status).not.toHaveBeenCalled()
  })

  it('entering Login invalidates the cache so login/logout get a fresh answer', async () => {
    vi.mocked(authApi.status).mockResolvedValue(statusResult(false))

    await router.push('/')
    await router.push('/history')
    expect(authApi.status).toHaveBeenCalledTimes(1)

    // 登录/登出动作的必经之路：进 Login 即清缓存。
    await router.push('/login')
    await router.push('/downloads')

    expect(router.currentRoute.value.name).toBe('Downloads')
    expect(authApi.status).toHaveBeenCalledTimes(2)
  })

  it('does not cache probe failures (server unreachable → Login, retried next hop)', async () => {
    vi.mocked(authApi.status).mockRejectedValueOnce(new Error('server down'))
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('Login')

    // 恢复后下一跳重新探测（失败结果未进缓存），成功即放行。
    vi.mocked(authApi.status).mockResolvedValue(statusResult(false))
    await router.push('/history')
    expect(router.currentRoute.value.name).toBe('History')
    expect(authApi.status).toHaveBeenCalledTimes(2)
  })
})
