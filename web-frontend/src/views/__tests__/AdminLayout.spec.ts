import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/router'

/**
 * A5-1: AdminLayout 已删除，/admin 子树并入 /settings（服务器分组 →
 * /settings/server/*）。本 spec 守护旧 /admin 链路的 redirect 兜底 ——
 * 含 HomeView 的 /admin/eh EH 会话跳转与外部书签深链。
 */
const REDIRECTS: Array<[string, string]> = [
  ['/admin', '/settings/server/download'],
  ['/admin/download', '/settings/server/download'],
  ['/admin/filter-slots', '/settings/server/filter-slots'],
  ['/admin/server', '/settings/server/server'],
  ['/admin/backup', '/settings/server/backup'],
  ['/admin/devices', '/settings/server/devices'],
  ['/admin/eh', '/settings/server/eh'],
  ['/admin/access', '/settings/server/access'],
  ['/admin/processing', '/settings/server/processing'],
  ['/admin/advanced', '/settings/server/advanced'],
  ['/admin/about', '/settings/server/about'],
]

function makeRouter() {
  return createRouter({ history: createMemoryHistory(), routes })
}

describe('legacy /admin redirects (A5-1 合并后深链兜底)', () => {
  it.each(REDIRECTS)('%s lands on %s', async (from, to) => {
    const router = makeRouter()
    await router.push(from)
    await router.isReady()
    expect(router.currentRoute.value.path).toBe(to)
  })

  it('redirects preserve the route query (deep links with params)', async () => {
    const router = makeRouter()
    await router.push('/admin/eh?foo=1')
    await router.isReady()
    expect(router.currentRoute.value.path).toBe('/settings/server/eh')
    expect(router.currentRoute.value.query.foo).toBe('1')
  })

  it('still sends unknown admin sub-paths to NotFound (catch-all)', async () => {
    const router = makeRouter()
    await router.push('/admin/does-not-exist')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('NotFound')
  })
})
