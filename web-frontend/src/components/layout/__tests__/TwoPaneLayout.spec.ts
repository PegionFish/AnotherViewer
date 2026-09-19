/**
 * TwoPaneLayout — 平板对齐（T3，2026-09-20 定案）共享双栏骨架。
 *
 * 断点：真实路由 + 可编程 matchMedia 桩（happy-dom 对 min-width 恒 true，
 * 必须钉死）。覆盖：宽/窄布局类与 slot 作用域、占位符 ⇄ 详情插槽切换、
 * Esc 拦截（宽屏 + 有选中，可编辑目标豁免，窄屏/无选中不挂监听）、
 * 浏览器返回拦截（popstate 窗口标记 + 路由离开守卫，真实 router 驱动）、
 * KeepAlive 停用摘监听（audit P1-5 同类）、跨阈值 DOM 常驻。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, ref, KeepAlive, type Component } from 'vue'
import { createRouter, createWebHistory, RouterView, type Router } from 'vue-router'
import TwoPaneLayout from '../TwoPaneLayout.vue'

/* ---------------------- 双栏断点桩（T3） ---------------------- */

type MqlListener = (event: MediaQueryListEvent) => void

let setWide: (matches: boolean) => void = () => {}

function stubMatchMedia(matches: boolean): void {
  const listeners = new Set<MqlListener>()
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      addEventListener: (_type: string, cb: MqlListener) => {
        listeners.add(cb)
      },
      removeEventListener: (_type: string, cb: MqlListener) => {
        listeners.delete(cb)
      },
    })),
  )
  setWide = (next: boolean) => {
    for (const cb of [...listeners]) cb({ matches: next } as MediaQueryListEvent)
  }
}

/* ---------------------- harness ---------------------- */

/** 宿主可编程状态：选中态 + clear-selection 记录（模块级，Harness 读取）。 */
const hasSelection = ref(false)
const clearSpy = vi.fn()

const Harness = defineComponent({
  name: 'TwoPaneHarness',
  setup() {
    return () =>
      h(
        TwoPaneLayout,
        {
          hasSelection: hasSelection.value,
          onClearSelection: () => clearSpy(),
        },
        {
          list: ({ wide }: { wide: boolean }) =>
            h('div', { 'data-testid': 'list-slot' }, wide ? 'list-wide' : 'list-narrow'),
          detail: () => h('div', { 'data-testid': 'detail-slot' }, 'detail-content'),
        },
      )
  },
})

const OtherView = defineComponent({
  name: 'OtherView',
  render: () => h('div', 'other'),
})

/** App.vue 同构：router-view + KeepAlive（缓存列表视图）。 */
const Shell = defineComponent({
  name: 'Shell',
  setup() {
    return () =>
      h(RouterView, null, {
        default: ({ Component }: { Component: Component }) => h(KeepAlive, () => h(Component)),
      })
  },
})

let router: Router
let wrapper: VueWrapper | undefined

async function mountShell(wide: boolean): Promise<void> {
  stubMatchMedia(wide)
  router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: Harness },
      { path: '/other', component: OtherView },
    ],
  })
  await router.push('/')
  await router.isReady()
  wrapper = mount(Shell, { global: { plugins: [router] } })
  await flushPromises()
}

beforeEach(() => {
  setActivePinia(createPinia())
  hasSelection.value = false
  clearSpy.mockClear()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
})

describe('TwoPaneLayout — 布局形态（≥960px 双栏，<960px 单列）', () => {
  it('窄屏：无双栏类，list 插槽收到 wide=false，右栏插槽不渲染（占位符出）', async () => {
    await mountShell(false)

    expect(wrapper!.find('[data-testid="two-pane"]').exists()).toBe(true)
    expect(wrapper!.find('.two-pane--wide').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="list-slot"]').text()).toBe('list-narrow')
    // 无选中：占位符（不自动选中），detail 插槽不渲染。
    expect(wrapper!.find('[data-testid="two-pane-placeholder"]').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="detail-slot"]').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="two-pane-detail"]').attributes('aria-hidden')).toBe(
      'true',
    )
  })

  it('宽屏：双栏类 + list 插槽收到 wide=true', async () => {
    await mountShell(true)

    expect(wrapper!.find('.two-pane--wide').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="list-slot"]').text()).toBe('list-wide')
    expect(wrapper!.find('[data-testid="two-pane-placeholder"]').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="two-pane-detail"]').attributes('aria-hidden')).toBe(
      'false',
    )
  })

  it('有选中：detail 插槽替换占位符', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    expect(wrapper!.find('[data-testid="detail-slot"]').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="two-pane-placeholder"]').exists()).toBe(false)
  })

  it('跨阈值来回：两列 DOM 常驻（类名切换，不销毁重建）', async () => {
    await mountShell(true)
    const listEl = wrapper!.find('[data-testid="two-pane-list"]').element
    expect(wrapper!.find('.two-pane--wide').exists()).toBe(true)

    setWide(false)
    await flushPromises()
    expect(wrapper!.find('.two-pane--wide').exists()).toBe(false)
    // DOM 常驻：同一元素（滚动位置/子状态天然保留）。
    expect(wrapper!.find('[data-testid="two-pane-list"]').element).toBe(listEl)

    setWide(true)
    await flushPromises()
    expect(wrapper!.find('.two-pane--wide').exists()).toBe(true)
    expect(wrapper!.find('[data-testid="two-pane-list"]').element).toBe(listEl)
  })
})

describe('TwoPaneLayout — Esc 拦截（宽屏有选中先清选中）', () => {
  it('宽屏 + 有选中：Esc → clear-selection', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(clearSpy).toHaveBeenCalledTimes(1)
  })

  it('宽屏 + 有选中：可编辑目标聚焦时 Esc 不劫持', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    const input = document.createElement('input')
    document.body.appendChild(input)
    // 在可编辑元素上派发（冒泡到 window）——event.target 才是 input。
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    input.remove()
    await flushPromises()
    expect(clearSpy).not.toHaveBeenCalled()
  })

  it('窄屏：无选中语义，Esc 不拦截', async () => {
    await mountShell(false)
    hasSelection.value = true // 宿主状态残留也只在宽屏生效
    await flushPromises()

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(clearSpy).not.toHaveBeenCalled()
  })

  it('宽屏 + 无选中：Esc 无事件；hasSelection 挂摘监听（remove-before-add）', async () => {
    await mountShell(true)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(clearSpy).not.toHaveBeenCalled()

    // 选中后监听挂上。
    hasSelection.value = true
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(clearSpy).toHaveBeenCalledTimes(1)

    // 清除选中后监听摘下。
    hasSelection.value = false
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(clearSpy).toHaveBeenCalledTimes(1)
  })
})

describe('TwoPaneLayout — 浏览器返回拦截（真实路由守卫）', () => {
  let forwardSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    forwardSpy = vi.spyOn(window.history, 'forward').mockImplementation(() => {})
  })

  afterEach(() => {
    forwardSpy.mockRestore()
  })

  it('宽屏 + 有选中：popstate 触发的离开被拦截为清除选中（路由不动）', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    // 模拟用户按浏览器返回：先 popstate（浏览器已弹栈），随后路由发起离开。
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
    await router.push('/other')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/')
    expect(clearSpy).toHaveBeenCalledTimes(1)
    // forward 复位弹栈（下一次 Back 才是真正的离开）。
    expect(forwardSpy).toHaveBeenCalledTimes(1)
  })

  it('无 popstate 的普通 push 不被吞（阅读器/详情/抽屉跳转照常）', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    await router.push('/other')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/other')
    expect(clearSpy).not.toHaveBeenCalled()
  })

  it('清除选中后的第二次离开放行（返回语义第二段）', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
    await router.push('/other')
    expect(router.currentRoute.value.fullPath).toBe('/')

    // 宿主响应 clear-selection 清掉选中 → 再离开不再拦截。
    hasSelection.value = false
    await flushPromises()
    await router.push('/other')
    expect(router.currentRoute.value.fullPath).toBe('/other')
  })

  it('窄屏：popstate 触发的离开不拦截（返回照旧）', async () => {
    await mountShell(false)
    hasSelection.value = true
    await flushPromises()

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
    await router.push('/other')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/other')
    expect(clearSpy).not.toHaveBeenCalled()
  })
})

describe('TwoPaneLayout — KeepAlive 停用守卫（audit P1-5 同类）', () => {
  it('停用（切到 /other）后 Esc / popstate 不再劫持；回激活恢复', async () => {
    await mountShell(true)
    hasSelection.value = true
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(clearSpy).toHaveBeenCalledTimes(1)
    clearSpy.mockClear()

    // 导航离开 → Harness 被 KeepAlive 缓存停用（实例仍存活）。
    await router.push('/other')
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))
    await flushPromises()
    expect(clearSpy).not.toHaveBeenCalled()

    // 回到本路由 → 重新激活，监听恢复（remove-before-add）。
    await router.push('/')
    await flushPromises()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(clearSpy).toHaveBeenCalledTimes(1)
  })
})
