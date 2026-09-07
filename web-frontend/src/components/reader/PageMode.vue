<template>
  <div
    ref="stageRef"
    class="page-mode"
    :class="{ 'page-mode--zoomed': pannable }"
    role="region"
    :aria-label="`Page ${page + 1} of ${totalPages}`"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <!-- :key remounts the frame per page so the enter animation replays -->
    <div :key="page" class="page-mode__frame" :class="enterClass">
      <img
        v-if="!error"
        ref="imgRef"
        class="page-mode__img"
        :class="[
          {
            'page-mode__img--loaded': loaded,
            'page-mode__img--gesture': gesturing,
          },
          scalingClass,
        ]"
        :src="src"
        :srcset="pageScaling === 'fit' ? srcset : undefined"
        :alt="`Page ${page + 1} of ${totalPages}`"
        :style="imgTransform"
        draggable="false"
        decoding="async"
        @load="onImageLoad"
        @error="onImageError"
      />
    </div>

    <Transition name="page-mode-fade">
      <div v-if="!loaded && !error" class="page-mode__overlay" aria-hidden="true">
        <ProgressSpinner size="large" />
      </div>
    </Transition>

    <!--
      A4: edge hot-zone hints — gradient + one-sided chevron, revealed on
      hover only (hidden on touch / hover-less devices via CSS). Purely
      visual affordance: clicks still bubble to the stage's tap zones, the
      strips only add the semantic resize cursors.
    -->
    <div class="page-mode__hint page-mode__hint--prev" aria-hidden="true">
      <!-- chevron_left -->
      <svg viewBox="0 0 24 24" focusable="false">
        <path
          d="M15 18l-6-6 6-6"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </div>
    <div class="page-mode__hint page-mode__hint--next" aria-hidden="true">
      <!-- chevron_right -->
      <svg viewBox="0 0 24 24" focusable="false">
        <path
          d="M9 6l6 6-6 6"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
    </div>

    <div v-if="error" class="page-mode__overlay page-mode__overlay--error" role="alert">
      <p class="page-mode__error-text">{{ errorText }}</p>
      <button type="button" class="page-mode__retry" @click="retry">重试</button>
    </div>
  </div>
</template>

<script lang="ts">
/**
 * Shared reader utilities — exported from this SFC's plain `<script>` block
 * (hoisted module scope) so sibling reader components (`DualPageMode`,
 * `ScrollMode`, `ImageReader`, `ReaderSettings`, `ReaderView`) can import
 * them WITHOUT creating a module cycle: `ImageReader` imports all three mode
 * components, so the shared code lives in a leaf they all already depend on.
 *
 * Design authority:
 * - Reading direction / page mode semantics: Android `GalleryActivity`
 *   (`READING_DIRECTION_*`, `PagerLayoutManager` / `ScrollLayoutManager` /
 *   `SpreadLayoutManager`).
 * - Image URLs: `contracts/openapi.yaml` `GET /api/v1/image/{galleryId}/{page}`
 *   (0-based page, `?w=` responsive width) + `contracts/responsive-strategy.md`
 *   §8 (reader srcset = container width, 1x/2x candidates).
 * - Dual-page pairing: `contracts/responsive-strategy.md` §6 — page 1 (cover)
 *   alone, pairs (2,3), (4,5)… i.e. 0-based pairs (1,2), (3,4)…
 */
import { onBeforeUnmount, onMounted } from 'vue'
import type { Ref } from 'vue'
import { useSwipeGesture } from '@/composables/useSwipeGesture'
import { useTapZoom } from '@/composables/useTapZoom'

/** Android `Settings.READING_DIRECTION_*`: LTR / RTL / vertical (scroll). */
export type ReadingDirection = 'ltr' | 'rtl' | 'vertical'

/** The two horizontal directions relevant to paged (non-scroll) modes. */
export type HorizontalDirection = 'ltr' | 'rtl'

/**
 * User-selected page mode preference. `auto` resolves by viewport
 * (responsive-strategy §6 rule 3: "force single / force dual / auto"):
 * landscape (aspect ≥ 1) → dual, portrait → single.
 */
export type PageModePref = 'auto' | 'single' | 'dual' | 'scroll'

/** The mode actually rendered after resolving `auto` + reading direction. */
export type ResolvedReaderMode = 'page' | 'dual' | 'scroll'

/** Auto-play (Android `auto_transfer`) state. */
export interface AutoPlayState {
  enabled: boolean
  intervalMs: number
}

export const READING_DIRECTIONS: readonly ReadingDirection[] = ['ltr', 'rtl', 'vertical']
export const PAGE_MODE_PREFS: readonly PageModePref[] = ['auto', 'single', 'dual', 'scroll']
export const AUTO_PLAY_INTERVALS_MS: readonly number[] = [2000, 3000, 5000, 8000]

/* ------------------------------------------------------------------------ */
/* Unified zoom semantics (plan-2026-09-05 A7 + 偏好接线批次)                 */
/*                                                                           */
/* The settings sheet, keyboard +/-, double-tap/click and pinch all move in  */
/* the same units: additive steps (default 0.25) within [0.5, maxZoom pref]; */
/* the double-tap cycle is 1 → 1.5 → 2 → 1; pinch never shrinks below fit    */
/* (clamped to [1, maxZoom pref]). READER_ZOOM_MAX / READER_ZOOM_STEP are    */
/* now the DEFAULTS / upper-bound reference — the live values come from the  */
/* reader.maxZoom / reader.zoomStep preferences (consumers clamp to ≥1 /     */
/* [0.05, 1] respectively).                                                  */
/* ------------------------------------------------------------------------ */

/** Lowest zoom — reachable via the settings sheet / keyboard (shrink page). */
export const READER_ZOOM_MIN = 0.5
/** 最高缩放的默认值/上限参考——生效值 = reader.maxZoom 偏好钳 [1, 本常量]。 */
export const READER_ZOOM_MAX = 3
/** 键盘/快捷面板 ± 的默认步长（加法倍率）——生效值 = reader.zoomStep 钳 [0.05, 1]。 */
export const READER_ZOOM_STEP = 0.25
/** Double-tap / double-click cycle: fit → 150% → 200% → fit. */
export const READER_DOUBLE_TAP_STEPS: readonly number[] = [1, 1.5, 2]
/** Pinch never shrinks below fit — its lower clamp differs from the keyboard. */
export const READER_PINCH_ZOOM_MIN = 1

/**
 * Responsive reader image URL — `streamGalleryImage` endpoint (0-based page)
 * with the `?w=` width hint (container width × DPR at the call site).
 *
 * 隐私打码不改 URL——页图请求照发（缓存预热/链路完整），像素由
 * `styles/privacy-mask.css`（<html>.privacy-mask 作用域）全局遮蔽。
 */
export function pageImageUrl(gid: number, page: number, widthPx: number): string {
  return `/api/v1/image/${gid}/${page}?w=${Math.max(1, Math.round(widthPx))}`
}

/**
 * Reader srcset per responsive-strategy.md §8: `1x` at container width,
 * `2x` at double. The browser picks by devicePixelRatio.
 */
export function pageImageSrcset(gid: number, page: number, cssWidth: number): string {
  const w1 = Math.max(1, Math.round(cssWidth))
  return `${pageImageUrl(gid, page, w1)} 1x, ${pageImageUrl(gid, page, w1 * 2)} 2x`
}

/**
 * Spread (dual-page) index containing a 0-based page.
 * firstPageCover=true（默认，现行为）: page 0 (cover) is spread 0 and displayed
 * alone; pages (1,2) → spread 1, (3,4) → spread 2, … matching "pairs (2,3),
 * (4,5)" in 1-based terms.
 * firstPageCover=false: the cover pairs with page 1 — spreads (0,1), (2,3), …
 * （两参版本供 ReaderView 翻页与 DualPageMode 铺摊共用，保证导航与渲染
 * 使用同一套换算，progress/页码语义不受影响。）
 */
export function spreadIndexOf(page: number, firstPageCover = true): number {
  if (!firstPageCover) return Math.floor(Math.max(page, 0) / 2)
  return page <= 0 ? 0 : Math.floor((page - 1) / 2) + 1
}

/** First (reading-order) 0-based page of a spread index. */
export function firstPageOfSpread(spread: number, firstPageCover = true): number {
  if (!firstPageCover) return Math.max(spread, 0) * 2
  return spread <= 0 ? 0 : 2 * spread - 1
}

/* ------------------------------------------------------------------------ */
/* Shared tap / swipe / wheel gesture handling                               */
/* ------------------------------------------------------------------------ */

export interface ReaderGestureOptions {
  /** The full-bleed gesture surface. */
  el: Ref<HTMLElement | null>
  /** RTL reading — mirrors tap zones and swipe direction. */
  isRtl: () => boolean
  /**
   * When true, single-tap zones, swipes and the wheel are ignored (zoomed:
   * the pointer is panning the image). Double-tap still works so the user
   * can zoom back out.
   */
  suppressed: () => boolean
  onPrev: () => void
  onNext: () => void
  onToggleChrome: () => void
  /** Double-tap action (zoom cycle); omit when the mode has no zoom. */
  onDoubleTap?: () => void
  /** Wave-1 1c tap-zone scheme provider (threeZone / edgeOnly / disabled). */
  tapZoneScheme?: () => string
}

export interface ReaderGestures {
  /** Swallow the click that follows a custom gesture (pinch / pan). */
  suppressTaps: () => void
}

/**
 * A3: wheel paging — one wheel gesture = one page. Leading-edge throttle:
 * the first event turns instantly, further events inside the window are
 * dropped (trackpads stream small deltas continuously).
 */
const WHEEL_PAGE_INTERVAL_MS = 150

/**
 * Tap-zone + swipe + wheel navigation shared by the paged reader modes:
 * - left ⅓ = prev, right ⅓ = next, center = toggle chrome (mirrored in RTL,
 *   like Tachiyomi/AnotherViewer RTL pagers);
 * - swipe left = next / swipe right = prev in LTR, reversed in RTL (the next
 *   page slides in from the left, so the finger sweeps right);
 * - desktop clicks act immediately with the zoom cycle on the native
 *   `dblclick` (plan-2026-09-05 A5, see `useTapZoom`);
 * - the wheel pages with the physical direction — down = next, deliberately
 *   NOT mirrored under RTL (plan-2026-09-05 A3).
 */
export function useReaderGestures(options: ReaderGestureOptions): ReaderGestures {
  // A5: single/double disambiguation (and the dblclick zoom channel) live in
  // the shared composable, which also owns the timer cleanup.
  const tap = useTapZoom(options.el, {
    onSingleTap: (x) => zoneAction(x),
    onDoubleTap: options.onDoubleTap,
    suppressed: options.suppressed,
  })

  /** Wave-1 1c: resolve a client-x coordinate to its tap-zone action. */
  function zoneAction(x: number) {
    const el = options.el.value
    if (!el) return
    const scheme = options.tapZoneScheme ? options.tapZoneScheme() : 'threeZone'
    if (scheme === 'disabled') {
      options.onToggleChrome()
      return
    }
    const edge = scheme === 'edgeOnly' ? 0.15 : 1 / 3
    const left = x - el.getBoundingClientRect().left
    const width = el.clientWidth
    if (left < width * edge) {
      ;(options.isRtl() ? options.onNext : options.onPrev)()
    } else if (left > width * (1 - edge)) {
      ;(options.isRtl() ? options.onPrev : options.onNext)()
    } else {
      options.onToggleChrome()
    }
  }

  useSwipeGesture(options.el, {
    onSwipeLeft: () => {
      tap.suppressTaps()
      if (options.suppressed()) return
      ;(options.isRtl() ? options.onPrev : options.onNext)()
    },
    onSwipeRight: () => {
      tap.suppressTaps()
      if (options.suppressed()) return
      ;(options.isRtl() ? options.onNext : options.onPrev)()
    },
  })

  // --- A3: wheel paging (paged modes only; ScrollMode keeps native scroll) -

  let lastWheelAt = 0

  function onWheel(event: WheelEvent) {
    // The stage never scrolls natively — keep the event from leaking to the
    // page behind the reader.
    event.preventDefault()
    // Zoomed in the wheel is noise while dragging; zones are suppressed too.
    if (options.suppressed()) return
    if (!event.deltaY) return
    const now = Date.now()
    if (now - lastWheelAt < WHEEL_PAGE_INTERVAL_MS) return
    lastWheelAt = now
    // 向下滚 = 下一页（物理直觉，不随 RTL 镜像）。
    if (event.deltaY > 0) options.onNext()
    else options.onPrev()
  }

  onMounted(() => {
    options.el.value?.addEventListener('wheel', onWheel, { passive: false })
  })

  onBeforeUnmount(() => {
    options.el.value?.removeEventListener('wheel', onWheel)
  })

  return { suppressTaps: tap.suppressTaps }
}
</script>

<script setup lang="ts">
// NOTE: `pageImageUrl`, `pageImageSrcset`, `useReaderGestures`, the
// `HorizontalDirection` / `ReaderGestures` types AND the `onMounted` /
// `onBeforeUnmount` lifecycle helpers come from the plain `<script>` block
// above — both blocks compile into ONE module, so re-importing them here
// would be a duplicate identifier (and a self-import would be a cycle).
import { computed, reactive, ref, watch } from 'vue'
import ProgressSpinner from '@/components/atoms/ProgressSpinner.vue'
import { availability } from '@/stores/availability'
import { usePreferencesStore } from '@/stores/preferences'

interface PageModeProps {
  gid: number
  /** 0-based page index. */
  page: number
  totalPages: number
  direction: HorizontalDirection
  /** Zoom factor (1 = fit). v-model:zoom. */
  zoom: number
  /** AI-enhanced hot-swap URLs keyed by 0-based page (websocket-protocol §3.3). */
  enhancedUrls?: ReadonlyMap<number, string>
}

interface PageModeEmits {
  (e: 'prev'): void
  (e: 'next'): void
  (e: 'toggle-chrome'): void
  (e: 'update:zoom', zoom: number): void
}

const preferencesStore = usePreferencesStore()

const props = withDefaults(defineProps<PageModeProps>(), {
  enhancedUrls: undefined,
})
const emit = defineEmits<PageModeEmits>()

const ZOOM_MIN = READER_PINCH_ZOOM_MIN
/** 生效最大缩放：reader.maxZoom 偏好钳 [1, READER_ZOOM_MAX]（活消费）。 */
const zoomMax = computed(() => {
  const raw = preferencesStore.prefs?.reader.maxZoom
  return typeof raw === 'number' && Number.isFinite(raw)
    ? Math.min(READER_ZOOM_MAX, Math.max(1, raw))
    : READER_ZOOM_MAX
})
/** Auto-retry budget for transient image failures before the error page. */
const MAX_AUTO_RETRIES = 3

const stageRef = ref<HTMLElement | null>(null)
const imgRef = ref<HTMLImageElement | null>(null)

const loaded = ref(false)
const error = ref(false)
/** 错误类别：generic=瞬态失败（走指数退避）；eh=EH 熔断（直接终态）。 */
const errorKind = ref<'generic' | 'eh'>('generic')
const hasLoaded = ref(false)
const retryTick = ref(0)
const panX = ref(0)
const panY = ref(0)
const gesturing = ref(false)
const enterClass = ref('')
/** Exponential-backoff retry state: a transient image failure (429 rate
 *  limit / 401 session-gated / brief network hiccup) auto-retries with
 *  growing delay before surfacing the manual-retry error page. */
const retryDelayMs = ref(0)
const retryAttempts = ref(0)
let retryTimer: ReturnType<typeof setTimeout> | undefined

/* ------------------------------------------------------------------ */
/* 页面缩放偏好（reader.pageScaling：fit|width|height|original）        */
/*                                                                     */
/* 此前该设置只有设置界面在读写，阅读器从未消费（死设置）。这里把它      */
/* 接成 <img> 的布局尺寸类；'fit' 保持既有 max-* + contain 行为，其余    */
/* 取消对应约束。非 fit 只是布局基准（plan-2026-09-05 A2）：不据此进入  */
/* 可平移态、也不抑制热区——真正放大（zoom>1）才切换到平移手势，否则     */
/* 单一条件会同时废掉触屏翻页与鼠标点击/滚轮。                          */
/* ------------------------------------------------------------------ */

const pageScaling = computed(() => preferencesStore.prefs?.reader.pageScaling ?? 'fit')

const scalingClass = computed(() => `page-mode__img--${pageScaling.value}`)

/** 真正放大（zoom>1）才进入可平移态：拖拽平移、抑制热区与滚轮翻页。 */
const pannable = computed(() => props.zoom > 1.001)

/* ------------------------------------------------------------------ */
/* Responsive image URLs (§8: container width × DPR)                   */
/* ------------------------------------------------------------------ */

const stageSize = reactive({ width: 800, height: 600 })
let stageObserver: ResizeObserver | null = null

function devicePixelRatio(): number {
  return typeof window !== 'undefined' && window.devicePixelRatio > 0
    ? window.devicePixelRatio
    : 1
}

const src = computed(() => {
  const enhanced = props.enhancedUrls?.get(props.page)
  if (enhanced) return enhanced
  const base = pageImageUrl(props.gid, props.page, stageSize.width * devicePixelRatio())
  return retryTick.value > 0 ? `${base}&_r=${retryTick.value}` : base
})

const srcset = computed(() =>
  props.enhancedUrls?.get(props.page)
    ? undefined
    : pageImageSrcset(props.gid, props.page, stageSize.width),
)

onMounted(() => {
  const el = stageRef.value
  if (!el) return
  stageSize.width = el.clientWidth || window.innerWidth
  stageSize.height = el.clientHeight || window.innerHeight
  if (typeof ResizeObserver !== 'undefined') {
    stageObserver = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect
      if (rect && rect.width > 0) {
        stageSize.width = rect.width
        stageSize.height = rect.height
      }
    })
    stageObserver.observe(el)
  }
})

onBeforeUnmount(() => {
  stageObserver?.disconnect()
})

/* ------------------------------------------------------------------ */
/* Load / error                                                        */
/* ------------------------------------------------------------------ */

// Only a PAGE change resets the load state (the :key remount shows the
// spinner). A src change from re-sizing keeps the previous bitmap visible
// while the sharper version loads — no spinner flash mid-read.
watch(
  () => props.page,
  () => {
    loaded.value = false
    error.value = false
    errorKind.value = 'generic'
    hasLoaded.value = false
    retryAttempts.value = 0
    retryDelayMs.value = 0
    // 每页从中性位置开始：翻页会显式清零平移并复位锚点状态（fit 下 zoom
    // 复位顺带清零，非 fit 缩放跨页保持 zoom=1，同样从中性位置起读）。
    panX.value = 0
    panY.value = 0
    userPanned.value = false
    if (retryTimer) clearTimeout(retryTimer)
    retryTimer = undefined
  },
)

function onImageLoad() {
  loaded.value = true
  hasLoaded.value = true
  clampPan()
  // 溢出时（放大 / 非 fit 布局）按偏好锚角就位；用户已手动平移则不打扰。
  if (!userPanned.value) applyStartAnchor()
}

function onImageError() {
  if (hasLoaded.value) {
    // websocket-protocol §3.3 step 5: a failing enhanced hot-swap must
    // silently keep the original — the element retains the old bitmap.
    console.debug('[PageMode] enhanced image failed to load, keeping original', props.page)
    return
  }
  // EH 熔断（plan-2026-08-30 §0/§3.2）：服务器已短路图片流（404
  // EH_UNAVAILABLE，只有此状态才会命中这里的 DOWN），不再进入指数退避自动重试
  // ——srcset 双 1x/2x 候选会连发多次。直接终态 + 专属文案，保留手动重试按钮。
  if (availability.state === 'down') {
    errorKind.value = 'eh'
    error.value = true
    return
  }
  // Transient failures (429 rate-limit / 401 session-gated / connectivity)
  // are retried with exponential backoff; only after the budget is exhausted
  // does the manual-retry error page appear. This absorbs the reader's
  // multi-width srcset retry storms without spamming the server.
  errorKind.value = 'generic'
  const delay = retryDelayMs.value === 0 ? 1000 : Math.min(retryDelayMs.value * 2, 8000)
  retryDelayMs.value = delay
  if (retryAttempts.value >= MAX_AUTO_RETRIES) {
    error.value = true
    return
  }
  retryAttempts.value += 1
  retryTimer = setTimeout(() => {
    retryTick.value += 1
    retryTimer = undefined
  }, delay)
}

const errorText = computed(() =>
  errorKind.value === 'eh'
    ? `第 ${props.page + 1} 页加载失败（EH 平台不可达）`
    : `第 ${props.page + 1} 页加载失败`,
)

function retry() {
  error.value = false
  errorKind.value = 'generic'
  retryTick.value += 1
  retryAttempts.value = 0
  retryDelayMs.value = 0
}

/* ------------------------------------------------------------------ */
/* Page-turn enter animation (slides in from the reading edge)         */
/* ------------------------------------------------------------------ */

watch(
  () => props.page,
  (next, prev) => {
    if (prev === undefined) return
    const forward = next > prev
    const fromRight = props.direction === 'rtl' ? !forward : forward
    enterClass.value = fromRight
      ? 'page-mode__frame--from-right'
      : 'page-mode__frame--from-left'
  },
)

/* ------------------------------------------------------------------ */
/* Zoom: double-tap/click cycle (1→1.5→2→1), pinch, settings (A7)      */
/* ------------------------------------------------------------------ */

function clampZoom(value: number): number {
  return Math.min(zoomMax.value, Math.max(ZOOM_MIN, value))
}

function cycleZoom() {
  const next = READER_DOUBLE_TAP_STEPS.find((step) => step > props.zoom + 0.01) ?? 1
  // 循环档位 [1,1.5,2] 不变，但非 1 终点受生效上限钳制（maxZoom 偏好）。
  emit('update:zoom', next > 1 ? Math.min(next, zoomMax.value) : 1)
}

/* ------------------------------------------------------------------ */
/* startPosition 偏好——App 语义平移：页面溢出视口时的初始对齐角          */
/*                                                                    */
/* App 端 startPosition 不是「起始页」而是 ImageView.setScaleOffset 的  */
/* 锚角（ImageView.java:426-447：TOP_RIGHT → dst.left = screenWidth -  */
/* targetWidth，即图片超出屏幕时先露出哪个角；PagerLayoutManager 对每   */
/* 一页 setScaleOffset 应用它）。Web 在 zoom=1 + fit 下图片不溢出，故   */
/* 只在放大/溢出时把未手动平移过的页面锚到偏好角；不参与起始页解析      */
/* （resolveStartPage 的深链 > 阅读进度优先级不受影响）。               */
/* ------------------------------------------------------------------ */

type StartAnchor = 'top-left' | 'top-right' | 'bottom-left' | 'bottom-right' | 'center'

const startAnchor = computed<StartAnchor>(() => {
  // 存量后端值是 snake_case（top_right），与设置页 normalizeStartPosition 同规则归一。
  const raw = (preferencesStore.prefs?.reader.startPosition ?? '').replace(/_/g, '-').toLowerCase()
  switch (raw) {
    case 'top-left':
    case 'top-right':
    case 'bottom-left':
    case 'bottom-right':
    case 'center':
      return raw
    default:
      // App ImageView 默认 START_POSITION_TOP_RIGHT（ImageView.java:60/94）。
      return 'top-right'
  }
})

/** 本页内用户已手动平移/捏合过——此后锚点不再自动回拉，直到翻页重置。 */
const userPanned = ref(false)

/** 把溢出量代入偏好锚角：translate(+max) 露出图左/上，−max 露出右/下。 */
function applyStartAnchor() {
  const img = imgRef.value
  const stage = stageRef.value
  if (!img || !stage) return
  const maxX = Math.max(0, (img.clientWidth * props.zoom - stage.clientWidth) / 2)
  const maxY = Math.max(0, (img.clientHeight * props.zoom - stage.clientHeight) / 2)
  const anchor = startAnchor.value
  panX.value = anchor.includes('left') ? maxX : anchor.includes('right') ? -maxX : 0
  panY.value = anchor.startsWith('top') ? maxY : anchor.startsWith('bottom') ? -maxY : 0
}

const imgTransform = computed(() => ({
  transform: `translate(${panX.value}px, ${panY.value}px) scale(${props.zoom})`,
}))

function clampPan() {
  const img = imgRef.value
  const stage = stageRef.value
  if (!img || !stage) return
  // clientWidth/Height are the layout (zoom-1) box — transform is visual only.
  const maxX = Math.max(0, (img.clientWidth * props.zoom - stage.clientWidth) / 2)
  const maxY = Math.max(0, (img.clientHeight * props.zoom - stage.clientHeight) / 2)
  panX.value = Math.min(maxX, Math.max(-maxX, panX.value))
  panY.value = Math.max(-maxY, Math.min(maxY, panY.value))
}

watch(
  () => props.zoom,
  () => {
    // 仅在完全无平移必要时归零（未放大）；放大且用户未手动平移时按偏好
    // 锚角定位（App setScaleOffset 每次缩放都重摆锚点），动过则只夹紧。
    if (!pannable.value) {
      panX.value = 0
      panY.value = 0
    } else if (!userPanned.value) {
      applyStartAnchor()
    } else {
      clampPan()
    }
  },
)

/* ------------------------------------------------------------------ */
/* Gestures: tap zones + swipe (shared), pinch + pan (local)           */
/* ------------------------------------------------------------------ */

const gestures: ReaderGestures = useReaderGestures({
  el: stageRef,
  isRtl: () => props.direction === 'rtl',
  // 仅真正放大时热区/滑动/滚轮让位给拖拽（A2；非 fit 缩放只是布局基准）。
  suppressed: () => pannable.value,
  onPrev: () => emit('prev'),
  onNext: () => emit('next'),
  onToggleChrome: () => emit('toggle-chrome'),
  onDoubleTap: cycleZoom,
  tapZoneScheme: () => preferencesStore.prefs?.reader.tapZoneScheme ?? 'threeZone',
})

// --- Pinch-to-zoom (two-finger touch) ---------------------------------

let pinchStartDist = 0
let pinchStartZoom = 1

function touchDistance(event: TouchEvent): number {
  const a = event.touches[0]
  const b = event.touches[1]
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY)
}

function onTouchStart(event: TouchEvent) {
  if (event.touches.length === 2) {
    pinchStartDist = touchDistance(event)
    pinchStartZoom = props.zoom
    gesturing.value = true
    gestures.suppressTaps()
  }
}

function onTouchMove(event: TouchEvent) {
  if (pinchStartDist > 0 && event.touches.length === 2) {
    const scale = touchDistance(event) / pinchStartDist
    // 捏合视为手动调整——此后锚点不再自动回拉（与本页拖拽同权）。
    userPanned.value = true
    emit('update:zoom', clampZoom(pinchStartZoom * scale))
    gestures.suppressTaps()
  }
}

function onTouchEnd(event: TouchEvent) {
  if (event.touches.length < 2 && pinchStartDist > 0) {
    pinchStartDist = 0
    pinchStartZoom = 1
    gesturing.value = false
  }
}

onMounted(() => {
  const el = stageRef.value
  if (!el) return
  el.addEventListener('touchstart', onTouchStart, { passive: true })
  el.addEventListener('touchmove', onTouchMove, { passive: true })
  el.addEventListener('touchend', onTouchEnd, { passive: true })
  el.addEventListener('touchcancel', onTouchEnd, { passive: true })
})

onBeforeUnmount(() => {
  const el = stageRef.value
  if (retryTimer) clearTimeout(retryTimer)
  if (!el) return
  el.removeEventListener('touchstart', onTouchStart)
  el.removeEventListener('touchmove', onTouchMove)
  el.removeEventListener('touchend', onTouchEnd)
  el.removeEventListener('touchcancel', onTouchEnd)
})

// --- Drag-to-pan while zoomed (pointer events: mouse + touch + pen) ----

let panPointerId: number | null = null
let panLastX = 0
let panLastY = 0

function onPointerDown(event: PointerEvent) {
  if (!pannable.value || pinchStartDist > 0) return
  // 进入拖拽平移即视为手动定位——锚点不再自动回拉。
  userPanned.value = true
  panPointerId = event.pointerId
  panLastX = event.clientX
  panLastY = event.clientY
  gesturing.value = true
  gestures.suppressTaps()
  stageRef.value?.setPointerCapture?.(event.pointerId)
}

function onPointerMove(event: PointerEvent) {
  if (panPointerId !== event.pointerId || pinchStartDist > 0) return
  panX.value += event.clientX - panLastX
  panY.value += event.clientY - panLastY
  panLastX = event.clientX
  panLastY = event.clientY
  clampPan()
}

function onPointerUp(event: PointerEvent) {
  if (panPointerId !== event.pointerId) return
  panPointerId = null
  gesturing.value = false
}
</script>

<style scoped>
.page-mode {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--grey-975);
  touch-action: none;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
  /* 舞台的可用高度（扣除底部拖动条）；dvh 跟随移动端地址栏收展。 */
  --page-fit-height: calc(100vh - var(--seekbar-panel-height));
}

@supports (height: 100dvh) {
  .page-mode {
    --page-fit-height: calc(100dvh - var(--seekbar-panel-height));
  }
}

.page-mode--zoomed {
  cursor: grab;
}

/* --- A4: edge hot-zone hints ------------------------------------------ */

/*
 * Hover-only affordance for the left/right tap zones: a subtle gradient plus
 * a single-sided chevron, faded in while the pointer rests on the edge.
 * The strip is a visual layer only — clicks bubble to the stage's tap-zone
 * handler; it exists to carry the semantic resize cursor. Hidden on touch /
 * hover-less devices (and while zoomed, when the zones yield to panning).
 */
.page-mode__hint {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  width: clamp(56px, 12%, 140px);
  color: rgba(255, 255, 255, 0.85);
  opacity: 0;
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.page-mode__hint svg {
  width: 28px;
  height: 28px;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.6));
}

.page-mode__hint--prev {
  left: 0;
  justify-content: flex-start;
  padding-left: 12px;
  cursor: w-resize;
  background: linear-gradient(to right, rgba(0, 0, 0, 0.4), transparent);
}

.page-mode__hint--next {
  right: 0;
  justify-content: flex-end;
  padding-right: 12px;
  cursor: e-resize;
  background: linear-gradient(to left, rgba(0, 0, 0, 0.4), transparent);
}

.page-mode__hint:hover {
  opacity: 1;
}

/* Touch / hover-less devices never see the hints. */
@media (hover: none), (pointer: coarse) {
  .page-mode__hint {
    display: none;
  }
}

/* Zoomed in the tap zones yield to panning — hide the affordance too. */
.page-mode--zoomed .page-mode__hint {
  display: none;
}

.page-mode__frame {
  display: flex;
  align-items: center;
  justify-content: center;
  /* 填满舞台：fit 图片需要能放大到舞台尺寸（漫画原图常小于平板视口，
     只用 max-* 约束时图片按原尺寸显示、四周全是黑边）。 */
  width: 100%;
  height: var(--page-fit-height);
}

.page-mode__frame--from-right {
  animation: page-mode-enter-right var(--duration-scene-translate)
    var(--ease-decelerate-quint);
}

.page-mode__frame--from-left {
  animation: page-mode-enter-left var(--duration-scene-translate)
    var(--ease-decelerate-quint);
}

/* 适应屏幕（fit）：铺满舞台框（object-fit contain 保持纵横比，可放大也可
   缩小——max-* 只缩不放是平板上"图片不适应屏幕"的根因）。 */
.page-mode__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 0;
  /* Wave-1 1c: pageTransition pref (slide/fade/none) via root CSS var. */
  transition: var(
    --reader-page-transition,
    transform var(--duration-scene-opacity) var(--ease-decelerate-quart),
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart)
  );
  will-change: transform;
}

/* reader.pageScaling（此前只有设置界面、阅读器未消费）：
   fit=铺满舞台框；width/height/original 解除对应方向的约束。
   注意：非 fit 只是布局基准（A2）——超出的部分不再启用 zoom=1 拖拽平移
   （那会同时废掉热区翻页）；要看全图请双击放大后拖拽。非 fit 模式需要
   布局盒语义（宽度撑满/原始尺寸），因此覆盖 fit 的 width/height:100%。 */
.page-mode__img--width {
  width: 100%;
  height: auto;
}

.page-mode__img--height {
  width: auto;
  height: var(--page-fit-height);
}

.page-mode__img--original {
  width: auto;
  height: auto;
}

.page-mode__img--loaded {
  opacity: 1;
}

/* No transform transition mid-gesture — the image must track the fingers. */
.page-mode__img--gesture {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.page-mode__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing);
}

.page-mode__error-text {
  color: var(--grey-450);
  font-size: var(--text-little-small);
}

.page-mode__retry {
  padding: 8px 28px;
  border: none;
  border-radius: var(--card-radius);
  background: var(--color-primary);
  color: var(--color-white);
  font-size: var(--text-small);
  font-weight: 500;
  cursor: pointer;
  transition:
    background 150ms var(--ease-decelerate-quart),
    transform 150ms var(--ease-decelerate-quart);
}

.page-mode__retry:hover {
  background: var(--color-primary-dark);
}

.page-mode__retry:active {
  transform: scale(0.97);
}

.page-mode__retry:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 2px;
}

.page-mode-fade-enter-active,
.page-mode-fade-leave-active {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.page-mode-fade-enter-from,
.page-mode-fade-leave-to {
  opacity: 0;
}

@keyframes page-mode-enter-right {
  from {
    transform: translateX(6%);
    opacity: 0.3;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

@keyframes page-mode-enter-left {
  from {
    transform: translateX(-6%);
    opacity: 0.3;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .page-mode__frame--from-right,
  .page-mode__frame--from-left {
    animation: none;
  }
}
</style>
