import { describe, expect, it, vi, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { h, defineComponent } from 'vue'
import { readFileSync } from 'fs'
import { resolve } from 'path'
import SettingsLayout from '../settings/SettingsLayout.vue'
import SettingsIndex from '../settings/SettingsIndex.vue'

/**
 * 与真实路由表同构的子页桩（避免懒加载真实页面拉 API）。
 * 挂载经由 `<router-view>`（真实 app 的渲染方式）——直接 mount 布局会让其
 * 内嵌 router-view 停在 depth 0 渲染父记录自身，形成嵌套假象。
 */
const stub = defineComponent({ render: () => null })
const Harness = defineComponent({ render: () => h(RouterView) })

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/settings',
        component: SettingsLayout,
        children: [
          { path: '', name: 'SettingsIndex', component: SettingsIndex },
          { path: 'general', component: stub },
          { path: 'reader', component: stub },
          { path: 'privacy', component: stub },
          { path: 'transfer', component: stub },
          { path: 'server/download', component: stub },
          { path: 'server/filter-slots', component: stub },
          { path: 'server/server', component: stub },
          { path: 'server/backup', component: stub },
          { path: 'server/devices', component: stub },
          { path: 'server/eh', component: stub },
          { path: 'server/access', component: stub },
          { path: 'server/processing', component: stub },
          { path: 'server/advanced', component: stub },
          { path: 'server/about', component: stub },
        ],
      },
    ],
  })
}

function stubMatchMedia(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
    })),
  )
}

function layoutCss(): string {
  return readFileSync(resolve(process.cwd(), 'src/views/settings/SettingsLayout.vue'), 'utf8')
}

describe('SettingsLayout (A5-1 合并 + A5-3 层级导航)', () => {
  let wrapper: VueWrapper

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  async function mountAt(path: string, wide = false) {
    stubMatchMedia(wide)
    const router = makeRouter()
    await router.push(path)
    await router.isReady()
    wrapper = mount(Harness, { global: { plugins: [router] } })
    await flushPromises()
    return router
  }

  it('renders the grouped two-pane sidebar with 14 links in 2 groups', async () => {
    await mountAt('/settings/general')
    expect(wrapper.find('[data-testid="settings-sidebar"]').exists()).toBe(true)
    expect(wrapper.findAll('.settings-layout__group-label').map((g) => g.text())).toEqual([
      '偏好',
      '服务器',
    ])
    expect(wrapper.findAll('.settings-layout__link')).toHaveLength(14)
  })

  it('marks the link matching the current route as active (server group)', async () => {
    await mountAt('/settings/server/eh')
    const active = wrapper
      .findAll('.settings-layout__link')
      .filter((link) => link.classes().includes('is-active'))
    expect(active).toHaveLength(1)
    expect(active[0].text()).toBe('EH 会话')
  })

  it('keeps /settings exact on the index route (no route-level redirect)', async () => {
    const router = await mountAt('/settings')
    expect(router.currentRoute.value.name).toBe('SettingsIndex')
    expect(router.currentRoute.value.path).toBe('/settings')
  })

  describe('narrow hierarchy navigation (A5-3)', () => {
    it('renders the grouped index page at /settings exact', async () => {
      await mountAt('/settings')
      const index = wrapper.find('[data-testid="settings-index"]')
      expect(index.exists()).toBe(true)
      expect(wrapper.findAll('[data-testid="settings-index-row"]')).toHaveLength(14)
      expect(wrapper.findAll('.settings-index__group-label').map((g) => g.text())).toEqual([
        '偏好',
        '服务器',
      ])
      // 索引页是根：无返回箭头。
      expect(wrapper.find('[data-testid="settings-back"]').exists()).toBe(false)
    })

    it('shows the back bar on sub-pages and hides it on the index', async () => {
      await mountAt('/settings/server/devices')
      expect(wrapper.find('[data-testid="settings-back"]').exists()).toBe(true)
      wrapper.unmount()

      await mountAt('/settings')
      expect(wrapper.find('[data-testid="settings-back"]').exists()).toBe(false)
    })

    it('goes back in history when there is a previous entry', async () => {
      const router = await mountAt('/settings')
      await router.push('/settings/server/eh')
      await flushPromises()
      expect(wrapper.find('[data-testid="settings-back"]').exists()).toBe(true)

      await wrapper.find('[data-testid="settings-back"]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/settings')
    })

    it('falls back to pushing the index when history has no previous entry (C5)', async () => {
      // 深链直达：首跳即子页，history.state.back 为空。
      const router = await mountAt('/settings/server/eh')
      expect(router.currentRoute.value.path).toBe('/settings/server/eh')

      await wrapper.find('[data-testid="settings-back"]').trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/settings')
      expect(router.currentRoute.value.name).toBe('SettingsIndex')
    })
  })

  describe('wide viewport (≥960px)', () => {
    it('replaces /settings exact with the default sub-page via matchMedia (no index render)', async () => {
      const router = await mountAt('/settings', true)
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/settings/general')
      // 不做 CSS 双渲染取巧：宽屏下索引页本体不渲染。
      expect(wrapper.find('[data-testid="settings-index"]').exists()).toBe(false)
    })

    it('does not hijack direct sub-page hits on wide viewports', async () => {
      const router = await mountAt('/settings/privacy', true)
      await flushPromises()
      expect(router.currentRoute.value.path).toBe('/settings/privacy')
    })
  })

  it('retires the tab bar CSS and keeps the hamburger-clearance contract', () => {
    const css = layoutCss()
    // 顶部横排标签条退役：右缘渐隐与横向滚动不再存在。
    expect(css).not.toContain('::after')
    expect(css).not.toContain('overflow-x: auto')
    // 返回条仍是 --hamburger-clearance 契约消费方（窄屏避让浮动汉堡）。
    expect(css).toContain('padding-left: var(--hamburger-clearance)')
  })
})
