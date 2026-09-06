import { describe, it, expect, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import {
  CANONICAL,
  EVAL_TOOLBAR_H,
  parseFrameQuery,
  useViewportFrame,
  type AspectRatio,
  type FrameState,
  type Orientation,
  type ScaleMode,
} from '../useViewportFrame'

type Frame = ReturnType<typeof useViewportFrame>

/** 当前挂载的宿主——afterEach 卸载，resize 监听不会跨用例堆积。 */
let active: VueWrapper | undefined

/** 挂载一个只装该 composable 的空宿主（onMounted 注册 resize 监听所需）。 */
function useFrame(initial?: Partial<FrameState>): Frame {
  let frame: Frame | undefined
  const Host = defineComponent({
    setup() {
      frame = useViewportFrame(initial)
      return () => h('div')
    },
  })
  active = mount(Host)
  return frame as Frame
}

/** happy-dom 下直接改写窗口尺寸（useEdgeBackGesture.spec 同款手法）。 */
function setWindow(w: number, h: number): void {
  Object.defineProperty(window, 'innerWidth', { value: w, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: h, configurable: true })
}

function fireResize(): void {
  window.dispatchEvent(new Event('resize'))
}

const RATIOS: readonly AspectRatio[] = ['16:9', '16:10', '4:3', '3:2']
const ORIENTATIONS: readonly Orientation[] = ['landscape', 'portrait']
const SCALE_MODES: readonly ScaleMode[] = ['fit', '100']
/** 消费方兜底默认（卡面契约：16:9 / landscape / fit / unlocked）。 */
const DEFAULTS: FrameState = {
  ratio: '16:9',
  orientation: 'landscape',
  scaleMode: 'fit',
  locked: false,
}

describe('useViewportFrame（评估台取景框）', () => {
  afterEach(() => {
    active?.unmount()
    active = undefined
  })

  describe('常量导出', () => {
    it('CANONICAL 四种比例的 canonical 尺寸', () => {
      expect(CANONICAL).toEqual({
        '16:9': { w: 1280, h: 720 },
        '16:10': { w: 1280, h: 800 },
        '4:3': { w: 1024, h: 768 },
        '3:2': { w: 1200, h: 800 },
      })
    })

    it('EVAL_TOOLBAR_H = 56（availH 推导用）', () => {
      expect(EVAL_TOOLBAR_H).toBe(56)
    })
  })

  describe('4 比例 × 2 方向的 width/height', () => {
    for (const ratio of RATIOS) {
      const { w, h } = CANONICAL[ratio]
      it(`${ratio} landscape → ${w}×${h}`, () => {
        const f = useFrame({ ratio })
        expect(f.ratio.value).toBe(ratio)
        expect(f.width.value).toBe(w)
        expect(f.height.value).toBe(h)
      })
      it(`${ratio} portrait → 宽高互换 ${h}×${w}`, () => {
        const f = useFrame({ ratio, orientation: 'portrait' })
        expect(f.orientation.value).toBe('portrait')
        expect(f.width.value).toBe(h)
        expect(f.height.value).toBe(w)
      })
    }
  })

  describe('fit / 100 缩放', () => {
    it('默认状态：16:9 / landscape / fit / unlocked', () => {
      setWindow(1280, 776) // avail = 1280×(776-56) = 1280×720
      const f = useFrame()
      expect(f.ratio.value).toBe('16:9')
      expect(f.orientation.value).toBe('landscape')
      expect(f.scaleMode.value).toBe('fit')
      expect(f.locked.value).toBe(false)
      expect(f.width.value).toBe(1280)
      expect(f.height.value).toBe(720)
      expect(f.scale.value).toBe(1)
    })

    it('fit 取精确 min：宽度受限（availW/width 更小）', () => {
      setWindow(800, 656) // avail 800×600：800/1280=0.625 < 600/720
      const f = useFrame()
      expect(f.scale.value).toBe(800 / 1280)
    })

    it('fit 取精确 min：高度受限（含 EVAL_TOOLBAR_H 扣除）', () => {
      setWindow(1280, 656) // avail 1280×600：600/720 < 1280/1280
      const f = useFrame()
      // 若未扣工具条高，值会是 656/720——精确断言同时钉死 -56 的推导。
      expect(f.scale.value).toBe(600 / 720)
    })

    it('portrait 尺寸互换后 fit 按新宽高重算', () => {
      setWindow(800, 656) // avail 800×600；portrait 720×1280
      const f = useFrame({ orientation: 'portrait' })
      expect(f.width.value).toBe(720)
      expect(f.height.value).toBe(1280)
      expect(f.scale.value).toBe(600 / 1280)
    })

    it('initial 传入初始状态时创建即算好 scale', () => {
      setWindow(1600, 1056) // avail 1600×1000；3:2 → min(4/3, 1.25)
      const f = useFrame({ ratio: '3:2', scaleMode: 'fit' })
      expect(f.scale.value).toBe(1000 / 800)
    })

    it("'100' 档恒为原始像素 1，resize 也不变", () => {
      setWindow(1600, 1056)
      const f = useFrame({ scaleMode: '100' })
      expect(f.scale.value).toBe(1)
      setWindow(320, 232)
      fireResize()
      expect(f.scale.value).toBe(1)
    })

    it('setRatio 即时换 width/height 并重算 fit', () => {
      setWindow(900, 856) // avail 900×800
      const f = useFrame()
      expect(f.scale.value).toBe(900 / 1280) // 16:9：min(0.703, 1.111)
      f.setRatio('4:3') // 1024×768 → min(900/1024, 800/768)
      expect(f.width.value).toBe(1024)
      expect(f.height.value).toBe(768)
      expect(f.scale.value).toBe(900 / 1024)
    })
  })

  describe('锁定语义', () => {
    it('锁定后 resize 不再重算，解锁立即恢复重算', () => {
      setWindow(1280, 776)
      const f = useFrame()
      expect(f.scale.value).toBe(1)
      f.toggleLock()
      expect(f.locked.value).toBe(true)

      setWindow(640, 328) // avail 640×272
      fireResize()
      expect(f.scale.value).toBe(1) // 冻结

      f.toggleLock()
      expect(f.locked.value).toBe(false)
      expect(f.scale.value).toBe(272 / 720) // 解锁即按当前窗口重算

      setWindow(320, 456) // avail 320×400
      fireResize()
      expect(f.scale.value).toBe(320 / 1280) // 解锁后恢复随 resize 重算
    })

    it('initial locked=true 也先算好初始档位，再冻结', () => {
      setWindow(1280, 776)
      const f = useFrame({ locked: true })
      expect(f.scale.value).toBe(1) // 16:9 / avail 1280×720 → 1
      setWindow(1024, 500)
      fireResize()
      expect(f.scale.value).toBe(1) // 锁定中不随 resize 变
    })

    it('上锁动作本身不改变当前 scale', () => {
      setWindow(800, 656)
      const f = useFrame()
      const before = f.scale.value
      f.toggleLock()
      expect(f.scale.value).toBe(before)
    })

    it('锁定中显式切换比例/方向/缩放档位仍更新 scale', () => {
      setWindow(1280, 776)
      const f = useFrame()
      f.toggleLock() // 冻结在 1
      setWindow(640, 328) // avail 640×272
      fireResize()
      expect(f.scale.value).toBe(1)

      f.toggleOrientation() // portrait 720×1280 → min(640/720, 272/1280)
      expect(f.width.value).toBe(720)
      expect(f.height.value).toBe(1280)
      expect(f.scale.value).toBe(272 / 1280)

      f.setRatio('4:3') // portrait 768×1024 → min(640/768, 272/1024)
      expect(f.width.value).toBe(768)
      expect(f.height.value).toBe(1024)
      expect(f.scale.value).toBe(272 / 1024)

      f.setScaleMode('100')
      expect(f.scale.value).toBe(1)
      f.setScaleMode('fit')
      expect(f.scale.value).toBe(272 / 1024)
    })

    it('锁定中 set 同值（非切换）不重算', () => {
      setWindow(1280, 776)
      const f = useFrame({ ratio: '4:3' }) // avail 1280×720 → min(1.25, 0.9375)
      f.toggleLock()
      setWindow(320, 232)
      fireResize()
      const frozen = f.scale.value
      f.setRatio('4:3')
      f.setOrientation('landscape')
      f.setScaleMode('fit')
      expect(f.scale.value).toBe(frozen)
    })
  })

  describe('toQuery 序列化', () => {
    it('键固定为 {ratio, orient, scale, locked}', () => {
      const f = useFrame({ ratio: '4:3', orientation: 'portrait', scaleMode: '100', locked: true })
      expect(f.toQuery()).toEqual({ ratio: '4:3', orient: 'port', scale: '100', locked: '1' })
      expect(Object.keys(f.toQuery()).sort()).toEqual(['locked', 'orient', 'ratio', 'scale'])
    })

    it('默认状态 → {16:9, land, fit, 0}', () => {
      const f = useFrame()
      expect(f.toQuery()).toEqual({ ratio: '16:9', orient: 'land', scale: 'fit', locked: '0' })
    })
  })

  describe('parseFrameQuery 解析', () => {
    it('逐字段合法值', () => {
      expect(parseFrameQuery({})).toEqual({})
      expect(parseFrameQuery({ ratio: '4:3' })).toEqual({ ratio: '4:3' })
      expect(parseFrameQuery({ orient: 'land' })).toEqual({ orientation: 'landscape' })
      expect(parseFrameQuery({ orient: 'port' })).toEqual({ orientation: 'portrait' })
      expect(parseFrameQuery({ scale: 'fit' })).toEqual({ scaleMode: 'fit' })
      expect(parseFrameQuery({ scale: '100' })).toEqual({ scaleMode: '100' })
      expect(parseFrameQuery({ locked: '1' })).toEqual({ locked: true })
      expect(parseFrameQuery({ locked: '0' })).toEqual({}) // 仅 '1' 合法，'0' 走默认
    })

    it('未知键被忽略', () => {
      expect(parseFrameQuery({ foo: 'bar', ratio: '3:2' })).toEqual({ ratio: '3:2' })
    })

    it('乱值/空串/非字符串逐字段回默认（不进返回值）', () => {
      const bad: readonly unknown[] = [
        42,
        0,
        null,
        undefined,
        true,
        '',
        ['land'],
        { v: 1 },
        'land ',
        'LAND',
      ]
      const extra: Record<string, readonly unknown[]> = {
        ratio: ['1/2', '16:10:9', '9:16'],
        orient: ['portrait', 'landscape', 'horizontal'],
        scale: ['FIT', '50', '100%', '1'],
        locked: ['true', '0', '01', '1.0', 'yes'],
      }
      for (const key of ['ratio', 'orient', 'scale', 'locked'] as const) {
        for (const v of [...bad, ...(extra[key] ?? [])]) {
          expect(parseFrameQuery({ [key]: v })).toEqual({})
        }
      }
    })

    it('混合：只有合法字段进返回值', () => {
      expect(parseFrameQuery({ ratio: 'nope', orient: 'port', scale: 100, locked: 'x' })).toEqual({
        orientation: 'portrait',
      })
    })
  })

  describe('query 往返（parseFrameQuery(toQuery()) 还原状态）', () => {
    for (const ratio of RATIOS) {
      for (const orientation of ORIENTATIONS) {
        for (const scaleMode of SCALE_MODES) {
          for (const locked of [false, true]) {
            it(`${ratio}/${orientation}/${scaleMode}/locked=${locked}`, () => {
              const f = useFrame({ ratio, orientation, scaleMode, locked })
              const restored: FrameState = { ...DEFAULTS, ...parseFrameQuery(f.toQuery()) }
              expect(restored).toEqual({ ratio, orientation, scaleMode, locked })
            })
          }
        }
      }
    }
  })
})
