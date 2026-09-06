import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * useViewportFrame.ts — 评估台视口取景框（PWA 多平台批次 C4）：
 *
 * 以「canonical 画布 + 整体缩放」的方式在窗口里摆放一张固定比例的取景框，
 * 消费方（W2 EvalView）直接按 width/height/scale 布局即可：
 *
 * - ratio：四种 canonical 比例（见 CANONICAL）；portrait 时宽高互换；
 * - scaleMode：'fit' = min(availW/width, availH/height) 适配窗口；
 *   '100' = 原始像素。可用空间默认取 window.innerWidth 与
 *   window.innerHeight - EVAL_TOOLBAR_H（评估台工具条占位）；
 * - locked：锁定后 window resize 不再重算 scale（width/height 本就只随
 *   比例/方向变）；切换比例/方向/缩放档位等显式操作仍更新 scale，
 *   解锁时立即按当前窗口恢复重算；
 * - 状态经 URL query 往返：toQuery()/parseFrameQuery()，键固定
 *   {ratio, orient, scale, locked}；非法/缺省字段不进返回值，由消费方
 *   回退默认（16:9 / landscape / fit / unlocked）。
 */
export type AspectRatio = '16:9' | '16:10' | '4:3' | '3:2'
export type Orientation = 'landscape' | 'portrait'
/** 'fit'：适配窗口；'100'：原始像素。 */
export type ScaleMode = 'fit' | '100'

/** 各比例的 canonical 布局尺寸（px）——width/height 的唯一来源。 */
export const CANONICAL: Record<AspectRatio, { w: number; h: number }> = {
  '16:9': { w: 1280, h: 720 },
  '16:10': { w: 1280, h: 800 },
  '4:3': { w: 1024, h: 768 },
  '3:2': { w: 1200, h: 800 },
}

/** 评估台工具条高度（px）——垂直可用空间 = innerHeight - EVAL_TOOLBAR_H。 */
export const EVAL_TOOLBAR_H = 56

export interface FrameState {
  ratio: AspectRatio
  orientation: Orientation
  scaleMode: ScaleMode
  locked: boolean
}

const RATIOS: readonly AspectRatio[] = ['16:9', '16:10', '4:3', '3:2']

function isNonEmptyString(v: unknown): v is string {
  return typeof v === 'string' && v !== ''
}

function isAspectRatio(v: string): v is AspectRatio {
  return (RATIOS as readonly string[]).includes(v)
}

function isScaleMode(v: string): v is ScaleMode {
  return v === 'fit' || v === '100'
}

/**
 * 解析 toQuery() 产出的 query。每个字段仅在「字符串、非空、值合法」时
 * 纳入返回的 Partial；非法/缺省字段直接缺席，消费方用默认值兜底。
 */
export function parseFrameQuery(q: Record<string, unknown>): Partial<FrameState> {
  const out: Partial<FrameState> = {}
  if (isNonEmptyString(q.ratio) && isAspectRatio(q.ratio)) out.ratio = q.ratio
  if (isNonEmptyString(q.orient)) {
    if (q.orient === 'land') out.orientation = 'landscape'
    else if (q.orient === 'port') out.orientation = 'portrait'
  }
  if (isNonEmptyString(q.scale) && isScaleMode(q.scale)) out.scaleMode = q.scale
  if (isNonEmptyString(q.locked) && q.locked === '1') out.locked = true
  return out
}

export function useViewportFrame(initial?: Partial<FrameState>) {
  const ratio = ref<AspectRatio>(initial?.ratio ?? '16:9')
  const orientation = ref<Orientation>(initial?.orientation ?? 'landscape')
  const scaleMode = ref<ScaleMode>(initial?.scaleMode ?? 'fit')
  const locked = ref(initial?.locked ?? false)

  /** 当前布局 px：portrait 时 w/h 互换，只随比例/方向变。 */
  const width = computed(() =>
    orientation.value === 'landscape' ? CANONICAL[ratio.value].w : CANONICAL[ratio.value].h,
  )
  const height = computed(() =>
    orientation.value === 'landscape' ? CANONICAL[ratio.value].h : CANONICAL[ratio.value].w,
  )

  const scale = ref(1)

  /**
   * fit 缩放：布局尺寸与可用空间全部走入参（推导逻辑可参数覆盖、便于
   * 测试复用），本体不直接依赖 window。
   */
  function fitScale(layoutW: number, layoutH: number, availW: number, availH: number): number {
    return Math.min(availW / layoutW, availH / layoutH)
  }

  /** 窗口可用空间；非 DOM 环境回退 0（真实与测试路径都不会走到）。 */
  function windowAvail(): { w: number; h: number } {
    if (typeof window === 'undefined') return { w: 0, h: 0 }
    return { w: window.innerWidth, h: window.innerHeight - EVAL_TOOLBAR_H }
  }

  /**
   * 重算 scale。locked 且非显式操作（force=false）时直接跳过——窗口
   * resize 不再改变锁定档位；显式操作传 force=true 仍然更新。
   */
  function recomputeScale(force = false) {
    if (locked.value && !force) return
    if (scaleMode.value === '100') {
      scale.value = 1
      return
    }
    const avail = windowAvail()
    scale.value = fitScale(width.value, height.value, avail.w, avail.h)
  }

  // 创建时即按当前窗口算好初始档位，不必等 mount（locked 只锁「不随
  // resize 变」，query 里没有可恢复的冻结数值，初始仍强制算一次）。
  recomputeScale(true)

  function setRatio(next: AspectRatio) {
    if (ratio.value === next) return
    ratio.value = next
    recomputeScale(true)
  }

  function setOrientation(next: Orientation) {
    if (orientation.value === next) return
    orientation.value = next
    recomputeScale(true)
  }

  function toggleOrientation() {
    setOrientation(orientation.value === 'landscape' ? 'portrait' : 'landscape')
  }

  function setScaleMode(next: ScaleMode) {
    if (scaleMode.value === next) return
    scaleMode.value = next
    recomputeScale(true)
  }

  function toggleLock() {
    locked.value = !locked.value
    // 上锁＝冻结当前档位（不重算）；解锁＝立即按当前窗口恢复重算。
    if (!locked.value) recomputeScale()
  }

  /** 序列化到 URL query，键固定 {ratio, orient, scale, locked}。 */
  function toQuery(): Record<string, string> {
    return {
      ratio: ratio.value,
      orient: orientation.value === 'landscape' ? 'land' : 'port',
      scale: scaleMode.value,
      locked: locked.value ? '1' : '0',
    }
  }

  // 只有 window resize 走非强制重算：locked 时内部直接跳过。
  function onResize() {
    recomputeScale()
  }

  onMounted(() => {
    window.addEventListener('resize', onResize)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', onResize)
  })

  return {
    ratio,
    orientation,
    scaleMode,
    locked,
    width,
    height,
    scale,
    setRatio,
    setOrientation,
    toggleOrientation,
    setScaleMode,
    toggleLock,
    toQuery,
  }
}
