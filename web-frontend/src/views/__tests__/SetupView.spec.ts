import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import SetupView from '../SetupView.vue'
import { routes } from '@/router'
import { SERVER_BASE_KEY, SERVER_CONFIGURED_KEY, getServerBase, isServerConfigured } from '@/stores/server'

/**
 * SetupView（C7）：
 * - 归一化预览 + 行内非法提示
 * - 测试连接三分支（HTTP 200 / 非 2xx / fetch reject）——裸 fetch 直连候选 base
 * - 保存调用顺序（setServerBase → configured 标记 → 清 token/username → reload）
 * - https→http 混合内容警告
 * - 「使用当前地址」预填
 * - /setup、/eval 路由注册
 *
 * localStorage 用 happy-dom 真 localStorage（src/test/setup.ts 注入），
 * beforeEach 清理；window.location 用可配置的 own property 影子桩
 * （happy-dom 的 location 是原型访问器，delete 后自动还原）。
 */

interface LocationStub {
  protocol: string
  origin: string
  reload: ReturnType<typeof vi.fn>
}

/** happy-dom 的 location 是 window 的 own 访问器属性——先留档，还原时原样装回。 */
const originalLocationDescriptor = Object.getOwnPropertyDescriptor(window, 'location')

/** 用 own data property 遮蔽原访问器；afterEach 按原 descriptor 装回。 */
function stubWindowLocation(overrides: Partial<LocationStub> = {}): LocationStub {
  const stub: LocationStub = {
    protocol: 'http:',
    origin: 'http://localhost:3000',
    reload: vi.fn(),
    ...overrides,
  }
  Object.defineProperty(window, 'location', { value: stub, configurable: true, writable: true })
  return stub
}

function restoreWindowLocation(): void {
  if (originalLocationDescriptor) {
    Object.defineProperty(window, 'location', originalLocationDescriptor)
  } else {
    delete (window as unknown as { location?: unknown }).location
  }
}

/** 最小 fetch Response 替身（不依赖环境的 Response 实现细节）。 */
function jsonResponse(status: number, body: unknown, ok = true): Response {
  return { ok, status, json: async () => body } as unknown as Response
}

let wrapper: VueWrapper | undefined

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  restoreWindowLocation()
  localStorage.clear()
  document.body.innerHTML = ''
})

describe('SetupView（服务器设置）', () => {
  async function mountView(): Promise<VueWrapper> {
    wrapper = mount(SetupView)
    await flushPromises()
    return wrapper
  }

  function input(w: VueWrapper) {
    return w.find('input[aria-label="服务器地址"]')
  }

  it('renders the frozen placeholder and the same-origin initial preview', async () => {
    const w = await mountView()
    expect(input(w).attributes('placeholder')).toBe('192.168.6.141:8081')
    expect(w.find('[data-testid="server-preview"]').text()).toContain('同源部署')
  })

  it('shows the normalized preview in real time', async () => {
    const w = await mountView()
    await input(w).setValue('192.168.6.141:8081')
    expect(w.find('[data-testid="server-preview"]').text()).toContain('http://192.168.6.141:8081')
    await input(w).setValue('https://example.com:8443/sub/path')
    // 只保留 origin（路径丢弃）、显式 https 保留。
    expect(w.find('[data-testid="server-preview"]').text()).toContain('https://example.com:8443')
    expect(w.find('[data-testid="server-preview"]').text()).not.toContain('/sub/path')
  })

  it('shows an inline red error for invalid input without throwing to the console', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const w = await mountView()

    await input(w).setValue('not a url')
    const error = w.find('.setup-view__error')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('无法解析')
    expect(w.find('[data-testid="server-preview"]').exists()).toBe(false)
    // 非法输入不允许保存/探活。
    expect(w.find('[data-testid="save-button"]').attributes('disabled')).toBeDefined()

    await input(w).setValue('ftp://host')
    expect(w.find('.setup-view__error').text()).toContain('仅支持 http/https')
    expect(consoleError).not.toHaveBeenCalled()
  })

  // ---- 测试连接三分支（裸 fetch 直连候选 base，任何 HTTP 响应 = 可达） ----

  it('probe: HTTP 200 counts as reachable and surfaces authRequired (需要登录)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { authenticated: false, authRequired: true }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const w = await mountView()
    await input(w).setValue('192.168.6.141:8081')
    await w.find('[data-testid="probe-button"]').trigger('click')
    await flushPromises()

    // 候选 base 直连，不是共享 axios client 的当前服务器。
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('http://192.168.6.141:8081/api/v1/auth/status')
    const text = w.find('[data-testid="probe-result"]').text()
    expect(text).toContain('服务器可达')
    expect(text).toContain('需要登录')
  })

  it('probe: non-2xx HTTP responses still count as reachable', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(401, { authenticated: false, authRequired: true }, false),
    )
    vi.stubGlobal('fetch', fetchMock)
    const w = await mountView()
    await input(w).setValue('192.168.6.141:8081')
    await w.find('[data-testid="probe-button"]').trigger('click')
    await flushPromises()

    const text = w.find('[data-testid="probe-result"]').text()
    expect(text).toContain('服务器可达（HTTP 401）')
    expect(text).toContain('需要登录')
  })

  it('probe: a 5xx with a non-JSON body still counts as reachable without auth info', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      json: async () => {
        throw new SyntaxError('Unexpected token < in JSON')
      },
    } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)
    const w = await mountView()
    await input(w).setValue('192.168.6.141:8081')
    await w.find('[data-testid="probe-button"]').trigger('click')
    await flushPromises()

    const text = w.find('[data-testid="probe-result"]').text()
    expect(text).toContain('服务器可达（HTTP 503）')
    expect(text).not.toContain('登录')
  })

  it('probe: fetch rejection (network layer) is unreachable', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchMock)
    const w = await mountView()
    await input(w).setValue('192.168.6.141:8081')
    await w.find('[data-testid="probe-button"]').trigger('click')
    await flushPromises()

    const text = w.find('[data-testid="probe-result"]').text()
    expect(text).toContain('无法连接')
    expect(text).not.toContain('服务器可达')
  })

  // ---- 保存：调用顺序冻结 ----

  it('save: setServerBase → configured mark → clear token/username → reload, in order', async () => {
    // 预置旧登录态。
    localStorage.setItem('token', 't0')
    localStorage.setItem('username', 'alice')
    const location = stubWindowLocation()
    const w = await mountView()

    // 逐键断言。注意：happy-dom 的 Storage 是返回 Proxy 的构造器（惰性方法
    // 绑定），必须 spy 实例而不是 prototype——prototype spy 会被先前访问留下
    // 的绑定副本绕过。
    const setItemSpy = vi.spyOn(localStorage, 'setItem')
    const removeItemSpy = vi.spyOn(localStorage, 'removeItem')
    setItemSpy.mockClear()
    removeItemSpy.mockClear()

    await input(w).setValue('192.168.6.141:8081')
    await w.find('[data-testid="save-button"]').trigger('click')

    // reload 恰好一次（整页重载，无热切换）。
    expect(location.reload).toHaveBeenCalledTimes(1)

    // 逐键断言。
    expect(setItemSpy).toHaveBeenNthCalledWith(1, SERVER_BASE_KEY, 'http://192.168.6.141:8081')
    expect(setItemSpy).toHaveBeenNthCalledWith(2, SERVER_CONFIGURED_KEY, '1')
    expect(removeItemSpy).toHaveBeenNthCalledWith(1, 'token')
    expect(removeItemSpy).toHaveBeenNthCalledWith(2, 'username')

    // 跨 spy 的全局先后：写地址/标记 → 清 token/username → reload。
    const setOrders = setItemSpy.mock.invocationCallOrder
    const removeOrders = removeItemSpy.mock.invocationCallOrder
    const reloadOrder = location.reload.mock.invocationCallOrder[0]
    expect(Math.max(...setOrders)).toBeLessThan(Math.min(...removeOrders))
    expect(Math.max(...removeOrders)).toBeLessThan(reloadOrder)

    // 终态。
    expect(getServerBase()).toBe('http://192.168.6.141:8081')
    expect(isServerConfigured()).toBe(true)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('username')).toBeNull()
  })

  // ---- 混合内容警告 ----

  it('https page → http target shows the persistent mixed-content warning but stays savable', async () => {
    stubWindowLocation({ protocol: 'https:', origin: 'https://app.example.com' })
    const w = await mountView()

    expect(w.find('.setup-view__warning').exists()).toBe(false)
    await input(w).setValue('192.168.6.141:8081')
    const warning = w.find('.setup-view__warning')
    expect(warning.exists()).toBe(true)
    expect(warning.text()).toContain('混合内容')
    // 允许保存（警告常显但不阻止）。
    expect(w.find('[data-testid="save-button"]').attributes('disabled')).toBeUndefined()

    // 目标也是 https 时无警告。
    await input(w).setValue('https://192.168.6.141:8081')
    expect(w.find('.setup-view__warning').exists()).toBe(false)
  })

  // ---- 使用当前地址 ----

  it('useCurrentAddress prefills the normalized page origin', async () => {
    stubWindowLocation({ origin: 'http://192.168.6.66:8080' })
    const w = await mountView()
    await w.find('[data-testid="use-current-button"]').trigger('click')

    expect((input(w).element as HTMLInputElement).value).toBe('http://192.168.6.66:8080')
    expect(w.find('[data-testid="server-preview"]').text()).toContain('http://192.168.6.66:8080')
  })
})

describe('路由注册（C7 预注册）', () => {
  let router: Router

  afterEach(async () => {
    // memory router 无需 destroy，交给 GC。
    router = undefined as unknown as Router
  })

  it('/setup resolves to the Setup route with a lazy-loaded SetupView', async () => {
    router = createRouter({ history: createMemoryHistory(), routes })
    // 导航前：components.default 仍是懒加载函数。
    const before = router.resolve('/setup')
    expect(before.name).toBe('Setup')
    expect(typeof before.matched[0]!.components!.default).toBe('function')
    // 导航后：懒组件被解析为 SetupView 本体。
    await router.push('/setup')
    await router.isReady()
    expect(router.currentRoute.value.name).toBe('Setup')
    const loaded = router.currentRoute.value.matched[0]!.components!.default as {
      __name?: string
    }
    expect(loaded.__name).toBe('SetupView')
  })

  it('/eval exists and resolves to the lazy-loaded EvalView component', async () => {
    router = createRouter({ history: createMemoryHistory(), routes })
    const resolved = router.resolve('/eval')
    expect(resolved.name).toBe('Eval')
    const loader = resolved.matched[0]!.components!.default
    expect(typeof loader).toBe('function')
    const mod = await (loader as () => Promise<{ default: { __name?: string } }>)()
    expect(mod.default.__name).toBe('EvalView')
  })

  it('keeps the catch-all as the last route after the C7 insertions', () => {
    const last = routes[routes.length - 1]
    expect(last.name).toBe('NotFound')
  })
})
