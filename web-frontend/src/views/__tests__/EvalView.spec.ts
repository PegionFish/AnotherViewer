/**
 * EvalView (W2 / C8) — 评估台视图接线：
 * query ↔ 取景框初始状态、角标文案、控件事件 → 状态/URL 双向、
 * 锁定语义（resize 不改档）、iframe/容器几何绑定、工具条控件渲染与事件、
 * 全屏/新标签/退出 handler（best-effort 静默降级、历史判断）。
 *
 * 取景框本体（CANONICAL/parseFrameQuery/锁定/序列化）已在
 * composables/__tests__/useViewportFrame.spec.ts 覆盖，这里只测视图接线。
 */
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import EvalView from '../EvalView.vue'
import EvalToolbar from '@/components/eval/EvalToolbar.vue'
import AppSegmented from '@/components/form/AppSegmented.vue'
import { EVAL_TOOLBAR_H } from '@/composables/useViewportFrame'

const ORIGINAL_W = window.innerWidth
const ORIGINAL_H = window.innerHeight

/** happy-dom 下直接改写窗口尺寸（useViewportFrame.spec 同款手法）。 */
function setWindow(w: number, h: number): void {
  Object.defineProperty(window, 'innerWidth', { value: w, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: h, configurable: true })
}

function fireResize(): void {
  window.dispatchEvent(new Event('resize'))
}

let router: Router
let wrapper: VueWrapper | undefined

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/eval', component: { template: '<div />' } },
      { path: '/', component: { template: '<div />' } },
    ],
  })
}

/** 以指定 query 挂载视图（真实内存路由，沿 SettingsLayout.spec 惯例）。 */
async function mountEval(query = ''): Promise<VueWrapper> {
  router = makeRouter()
  await router.push(`/eval${query}`)
  await router.isReady()
  wrapper = mount(EvalView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** fit 档预期百分比：与 useViewportFrame 的 min(availW/w, availH/h) 同式。 */
function fitPct(w: number, h: number, availW: number, availH: number): number {
  return Math.round(Math.min(availW / w, availH / h) * 100)
}

function toolbar() {
  return wrapper!.findComponent(EvalToolbar)
}

function badge(): string {
  return wrapper!.find('.eval-stage__badge').text()
}

function frameStyleAttr(): string {
  return wrapper!.find('.eval-frame').attributes('style') ?? ''
}

describe('EvalView（评估台）', () => {
  beforeEach(() => {
    setWindow(1024, 768) // 固定 happy-dom 视口，让 fit 档可推导
    history.replaceState(null, '') // 清掉上个用例可能塞入的 history state
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    setWindow(ORIGINAL_W, ORIGINAL_H)
    vi.restoreAllMocks()
  })

  describe('挂载：query → 初始状态', () => {
    it('?ratio=4:3&orient=port → 768×1024，角标文案正确', async () => {
      await mountEval('?ratio=4:3&orient=port')

      const iframe = wrapper!.find('iframe')
      expect(iframe.attributes('width')).toBe('768')
      expect(iframe.attributes('height')).toBe('1024')
      const pct = fitPct(768, 1024, 1024, 768 - EVAL_TOOLBAR_H)
      expect(badge()).toBe(`768×1024 · 4:3 · 竖屏 · 适应 ${pct}%`)
    })

    it('无 query → 默认 16:9 / 横屏，1280×720', async () => {
      await mountEval()

      expect(wrapper!.find('iframe').attributes('width')).toBe('1280')
      expect(wrapper!.find('iframe').attributes('height')).toBe('720')
      const pct = fitPct(1280, 720, 1024, 768 - EVAL_TOOLBAR_H)
      expect(badge()).toBe(`1280×720 · 16:9 · 横屏 · 适应 ${pct}%`)
    })

    it('初始状态透传给 EvalToolbar（受控 props）', async () => {
      await mountEval('?ratio=4:3&orient=port&scale=100&locked=1')

      expect(toolbar().props('ratio')).toBe('4:3')
      expect(toolbar().props('orientation')).toBe('portrait')
      expect(toolbar().props('scaleMode')).toBe('100')
      expect(toolbar().props('locked')).toBe(true)
      expect(badge()).toBe('768×1024 · 4:3 · 竖屏 · 100%')
    })
  })

  describe('控件事件 → 状态/角标更新', () => {
    it('切比例：width/height/角标同步更新', async () => {
      await mountEval()
      toolbar().vm.$emit('update:ratio', '16:10')
      await flushPromises()

      const iframe = wrapper!.find('iframe')
      expect(iframe.attributes('width')).toBe('1280')
      expect(iframe.attributes('height')).toBe('800')
      const pct = fitPct(1280, 800, 1024, 768 - EVAL_TOOLBAR_H)
      expect(badge()).toBe(`1280×800 · 16:10 · 横屏 · 适应 ${pct}%`)
    })

    it('切方向：宽高互换、角标改竖屏', async () => {
      await mountEval('?ratio=3:2')
      toolbar().vm.$emit('update:orientation', 'portrait')
      await flushPromises()

      const iframe = wrapper!.find('iframe')
      expect(iframe.attributes('width')).toBe('800')
      expect(iframe.attributes('height')).toBe('1200')
      const pct = fitPct(800, 1200, 1024, 768 - EVAL_TOOLBAR_H)
      expect(badge()).toBe(`800×1200 · 3:2 · 竖屏 · 适应 ${pct}%`)
    })

    it('切缩放档 100%：角标显示 100%、容器 transform 为 scale(1)', async () => {
      await mountEval()
      toolbar().vm.$emit('update:scaleMode', '100')
      await flushPromises()

      expect(badge()).toBe('1280×720 · 16:9 · 横屏 · 100%')
      expect(frameStyleAttr()).toContain('transform: scale(1)')
    })
  })

  describe('锁定语义（视图层）', () => {
    it('锁定后 window resize 不改变 scale，解锁立即恢复重算', async () => {
      setWindow(1024, 768)
      await mountEval()
      const lockedTransform = frameStyleAttr() // scale = 1024/1280 = 0.8

      toolbar().vm.$emit('toggle-lock')
      await flushPromises()
      expect(toolbar().props('locked')).toBe(true)

      setWindow(400, 300) // 若未锁定会变成 400/1280 = 0.3125
      fireResize()
      await flushPromises()
      expect(frameStyleAttr()).toBe(lockedTransform) // 冻结在 0.8

      toolbar().vm.$emit('toggle-lock') // 解锁 → 按当前窗口立即重算
      await flushPromises()
      expect(frameStyleAttr()).toContain('transform: scale(0.3125)')
    })
  })

  describe('状态 ↔ URL query 双向', () => {
    it('状态变化调用 router.replace 且 query 正确（并真实落到路由）', async () => {
      await mountEval()
      const replaceSpy = vi.spyOn(router, 'replace')

      toolbar().vm.$emit('update:ratio', '4:3')
      await flushPromises()
      expect(replaceSpy).toHaveBeenCalledTimes(1)
      expect(replaceSpy).toHaveBeenCalledWith({
        query: { ratio: '4:3', orient: 'land', scale: 'fit', locked: '0' },
      })
      expect(router.currentRoute.value.query).toEqual({
        ratio: '4:3',
        orient: 'land',
        scale: 'fit',
        locked: '0',
      })

      // 同一 tick 内的两处变化被 watcher 批处理为一次 replace，落库为最终态。
      toolbar().vm.$emit('update:orientation', 'portrait')
      toolbar().vm.$emit('toggle-lock')
      await flushPromises()
      expect(replaceSpy).toHaveBeenCalledTimes(2)
      expect(replaceSpy).toHaveBeenLastCalledWith({
        query: { ratio: '4:3', orient: 'port', scale: 'fit', locked: '1' },
      })
      expect(router.currentRoute.value.query).toEqual({
        ratio: '4:3',
        orient: 'port',
        scale: 'fit',
        locked: '1',
      })
    })

    it('锁定中 resize 只冻结档位，不触发 router.replace（scale 不进 query）', async () => {
      await mountEval()
      const replaceSpy = vi.spyOn(router, 'replace')
      toolbar().vm.$emit('toggle-lock')
      await flushPromises()
      expect(replaceSpy).toHaveBeenCalledTimes(1) // locked: '1'

      setWindow(640, 480)
      fireResize()
      await flushPromises()
      expect(replaceSpy).toHaveBeenCalledTimes(1) // 无新状态变化
    })
  })

  describe('iframe 与容器几何绑定', () => {
    it('src 恒为 "/"，状态变化绝不改写（不触发重载）', async () => {
      await mountEval()
      const iframe = wrapper!.find('iframe')
      expect(iframe.attributes('src')).toBe('/')

      toolbar().vm.$emit('update:ratio', '4:3')
      toolbar().vm.$emit('update:orientation', 'portrait')
      toolbar().vm.$emit('update:scaleMode', '100')
      await flushPromises()
      expect(iframe.attributes('src')).toBe('/')
    })

    it('allow="fullscreen"，且不加 sandbox（需登录态与全屏）', async () => {
      await mountEval()
      const iframe = wrapper!.find('iframe')
      expect(iframe.attributes('allow')).toBe('fullscreen')
      expect(iframe.attributes('sandbox')).toBeUndefined()
    })

    it('容器 style：布局 px 宽高 + scale(scale) 缩放', async () => {
      setWindow(1024, 768)
      await mountEval()

      const style = frameStyleAttr()
      expect(style).toContain('width: 1280px')
      expect(style).toContain('height: 720px')
      const expectedScale = Math.min(1024 / 1280, (768 - EVAL_TOOLBAR_H) / 720)
      expect(style).toContain(`transform: scale(${expectedScale})`)
    })
  })

  describe('全屏 / 新标签 / 退出 handler', () => {
    it('全屏：容器 requestFullscreen + 方向锁定，按当前方向传参', async () => {
      await mountEval('?orient=land')
      const requestFs = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(wrapper!.find('.eval-frame').element, 'requestFullscreen', {
        value: requestFs,
        configurable: true,
      })
      const lock = vi.fn().mockResolvedValue(undefined)
      Object.defineProperty(screen, 'orientation', { value: { lock }, configurable: true })

      toolbar().vm.$emit('fullscreen')
      await flushPromises()
      expect(requestFs).toHaveBeenCalledTimes(1)
      expect(lock).toHaveBeenCalledWith('landscape')

      toolbar().vm.$emit('update:orientation', 'portrait')
      await flushPromises()
      toolbar().vm.$emit('fullscreen')
      await flushPromises()
      expect(lock).toHaveBeenLastCalledWith('portrait')
    })

    it('全屏 best-effort：requestFullscreen reject 时静默降级不抛错', async () => {
      await mountEval()
      Object.defineProperty(wrapper!.find('.eval-frame').element, 'requestFullscreen', {
        value: vi.fn().mockRejectedValue(new Error('denied')),
        configurable: true,
      })
      Object.defineProperty(screen, 'orientation', {
        value: { lock: vi.fn().mockRejectedValue(new Error('unsupported')) },
        configurable: true,
      })

      toolbar().vm.$emit('fullscreen')
      await flushPromises() // 无未处理拒绝 = 静默降级
      expect(badge()).toContain('16:9') // 视图状态不受影响
    })

    it('新标签打开：同源直读 iframe 路径，读不到回退 "/"', async () => {
      await mountEval()
      const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)

      toolbar().vm.$emit('open-new-tab')
      expect(openSpy).toHaveBeenCalledTimes(1)
      expect(openSpy).toHaveBeenCalledWith('/')
    })

    it('退出：无历史 router.push("/")', async () => {
      await mountEval()
      const pushSpy = vi.spyOn(router, 'push')
      const backSpy = vi.spyOn(router, 'back')

      toolbar().vm.$emit('exit')
      expect(pushSpy).toHaveBeenCalledWith('/')
      expect(backSpy).not.toHaveBeenCalled()
    })

    it('退出：有历史（history.state.position > 0）router.back()', async () => {
      await mountEval()
      history.replaceState({ position: 3 }, '')
      const pushSpy = vi.spyOn(router, 'push')
      const backSpy = vi.spyOn(router, 'back')

      toolbar().vm.$emit('exit')
      expect(backSpy).toHaveBeenCalledTimes(1)
      expect(pushSpy).not.toHaveBeenCalled()
    })
  })

  describe('EvalToolbar 控件', () => {
    async function mountToolbar(props = {}) {
      wrapper = mount(EvalToolbar, {
        props: {
          ratio: '16:9',
          orientation: 'landscape',
          scaleMode: 'fit',
          locked: false,
          ...props,
        },
      })
      await flushPromises()
      return wrapper
    }

    function segmentedByLabel(t: VueWrapper, label: string) {
      return t
        .findAllComponents(AppSegmented)
        .find((group) => group.attributes('aria-label') === label)!
    }

    it('三组 segmented：4 比例 / 横竖 / 适应-100%', async () => {
      const t = await mountToolbar()
      expect(t.findAllComponents(AppSegmented)).toHaveLength(3)

      const labels = (group: VueWrapper) =>
        group.findAll('.app-segmented__btn').map((btn) => btn.text())
      expect(labels(segmentedByLabel(t, '画面比例'))).toEqual(['16:9', '16:10', '4:3', '3:2'])
      expect(labels(segmentedByLabel(t, '画面方向'))).toEqual(['横屏', '竖屏'])
      expect(labels(segmentedByLabel(t, '缩放档位'))).toEqual(['适应', '100%'])
    })

    it('点选比例段 → update:ratio 带字面量值', async () => {
      const t = await mountToolbar()
      const group = segmentedByLabel(t, '画面比例')
      await group.findAll('.app-segmented__btn')[2].trigger('click') // 4:3
      expect(t.emitted('update:ratio')).toEqual([['4:3']])
    })

    it('锁定按钮视觉反映锁定态（aria-pressed + 高亮类）并回抛 toggle-lock', async () => {
      const t = await mountToolbar()
      const lockBtn = t.find('.eval-toolbar__btn')
      expect(lockBtn.attributes('aria-pressed')).toBe('false')
      expect(lockBtn.classes()).not.toContain('eval-toolbar__btn--on')

      await t.setProps({ locked: true })
      expect(lockBtn.attributes('aria-pressed')).toBe('true')
      expect(lockBtn.classes()).toContain('eval-toolbar__btn--on')

      await lockBtn.trigger('click')
      expect(t.emitted('toggle-lock')).toHaveLength(1)
    })

    it('全屏 / 新标签打开 / 退出按钮各自回抛事件', async () => {
      const t = await mountToolbar()
      const buttons = t.findAll('.eval-toolbar__btn')
      expect(buttons.map((btn) => btn.text())).toEqual(['锁定', '全屏', '新标签打开', '退出'])

      await buttons[1].trigger('click')
      expect(t.emitted('fullscreen')).toHaveLength(1)
      await buttons[2].trigger('click')
      expect(t.emitted('open-new-tab')).toHaveLength(1)
      await buttons[3].trigger('click')
      expect(t.emitted('exit')).toHaveLength(1)
    })

    it('工具条高度 = EVAL_TOOLBAR_H（与可用空间推导同源）', async () => {
      const t = await mountToolbar()
      expect(t.find('.eval-toolbar').attributes('style')).toContain(`height: ${EVAL_TOOLBAR_H}px`)
    })
  })
})
