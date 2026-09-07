<template>
  <div
    ref="rootRef"
    class="dual-page"
    :class="{ 'dual-page--rtl': direction === 'rtl' }"
    role="region"
    :aria-label="spreadLabel"
  >
    <!-- :key remounts the row per spread so the enter animation replays -->
    <div :key="spread.first" class="dual-page__row" :class="enterClass">
      <!-- Cover / trailing odd page: displayed alone, centered (responsive-strategy §6 rule 4) -->
      <div v-if="spread.alone" class="dual-page__slot dual-page__slot--alone">
        <Transition name="dual-page-fade">
          <div v-if="!loadedPages.has(spread.first)" class="dual-page__loading" aria-hidden="true">
            <ProgressSpinner size="small" />
          </div>
        </Transition>
        <!--
          splitWidePages：宽幅页（宽高比 > 1.2）拆左右两半并排铺满整行，
          页码/进度语义不变（仍是同一页索引）。左半在视图左、右半在右，
          不随 RTL 再镜像（行内页面顺序已由现有 RTL 槽位分配负责）。
        -->
        <div
          v-if="isSplit(spread.first)"
          class="dual-page__split"
          :style="{ aspectRatio: splitAspect(spread.first) }"
        >
          <div
            v-for="half in SPLIT_HALVES"
            :key="half"
            class="dual-page__half"
            :class="{ 'dual-page__half--right': half === 'right' }"
          >
            <img
              class="dual-page__split-img"
              :class="{ 'dual-page__split-img--loaded': loadedPages.has(spread.first) }"
              :src="srcForFull(spread.first)"
              :srcset="srcsetForFull(spread.first)"
              :alt="`Page ${spread.first + 1} of ${totalPages}`"
              draggable="false"
              decoding="async"
              @load="onPageLoad(spread.first, $event)"
            />
          </div>
        </div>
        <img
          v-else
          class="dual-page__img dual-page__img--alone"
          :class="{ 'dual-page__img--loaded': loadedPages.has(spread.first) }"
          :src="srcFor(spread.first)"
          :srcset="srcsetFor(spread.first)"
          :alt="`Page ${spread.first + 1} of ${totalPages}`"
          draggable="false"
          decoding="async"
          @load="onPageLoad(spread.first, $event)"
        />
      </div>

      <!-- Paired spread: two pages side by side (order mirrored in RTL) -->
      <template v-else>
        <div class="dual-page__slot">
          <Transition name="dual-page-fade">
            <div v-if="!loadedPages.has(spread.left)" class="dual-page__loading" aria-hidden="true">
              <ProgressSpinner size="small" />
            </div>
          </Transition>
          <div
            v-if="isSplit(spread.left)"
            class="dual-page__split"
            :style="{ aspectRatio: splitAspect(spread.left) }"
          >
            <div
              v-for="half in SPLIT_HALVES"
              :key="half"
              class="dual-page__half"
              :class="{ 'dual-page__half--right': half === 'right' }"
            >
              <img
                class="dual-page__split-img"
                :class="{ 'dual-page__split-img--loaded': loadedPages.has(spread.left) }"
                :src="srcForFull(spread.left)"
                :srcset="srcsetForFull(spread.left)"
                :alt="`Page ${spread.left + 1} of ${totalPages}`"
                draggable="false"
                decoding="async"
                @load="onPageLoad(spread.left, $event)"
              />
            </div>
          </div>
          <img
            v-else
            class="dual-page__img"
            :class="{ 'dual-page__img--loaded': loadedPages.has(spread.left) }"
            :src="srcFor(spread.left)"
            :srcset="srcsetFor(spread.left)"
            :alt="`Page ${spread.left + 1} of ${totalPages}`"
            draggable="false"
            decoding="async"
            @load="onPageLoad(spread.left, $event)"
          />
        </div>
        <div class="dual-page__slot">
          <Transition name="dual-page-fade">
            <div v-if="!loadedPages.has(spread.right)" class="dual-page__loading" aria-hidden="true">
              <ProgressSpinner size="small" />
            </div>
          </Transition>
          <div
            v-if="isSplit(spread.right)"
            class="dual-page__split"
            :style="{ aspectRatio: splitAspect(spread.right) }"
          >
            <div
              v-for="half in SPLIT_HALVES"
              :key="half"
              class="dual-page__half"
              :class="{ 'dual-page__half--right': half === 'right' }"
            >
              <img
                class="dual-page__split-img"
                :class="{ 'dual-page__split-img--loaded': loadedPages.has(spread.right) }"
                :src="srcForFull(spread.right)"
                :srcset="srcsetForFull(spread.right)"
                :alt="`Page ${spread.right + 1} of ${totalPages}`"
                draggable="false"
                decoding="async"
                @load="onPageLoad(spread.right, $event)"
              />
            </div>
          </div>
          <img
            v-else
            class="dual-page__img"
            :class="{ 'dual-page__img--loaded': loadedPages.has(spread.right) }"
            :src="srcFor(spread.right)"
            :srcset="srcsetFor(spread.right)"
            :alt="`Page ${spread.right + 1} of ${totalPages}`"
            draggable="false"
            decoding="async"
            @load="onPageLoad(spread.right, $event)"
          />
        </div>
      </template>
    </div>

    <!--
      A4: edge hot-zone hints — same hover-only affordance as PageMode
      (gradient + chevron + resize cursor, hidden on touch devices). Visual
      only; clicks bubble to the stage's tap-zone handler.
    -->
    <div class="dual-page__hint dual-page__hint--prev" aria-hidden="true">
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
    <div class="dual-page__hint dual-page__hint--next" aria-hidden="true">
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
  </div>
</template>

<script setup lang="ts">
/**
 * DualPageMode.vue — two pages side by side, replicating Android
 * `SpreadLayoutManager` (GalleryView `LAYOUT_DUAL_PAGE`):
 *
 * - Page pairing follows `firstPageCover`（reader 偏好，活消费）：默认 true
 *   时 page 1 (0-based 0, the cover) is displayed ALONE and centered, pairing
 *   starts after it — 1-based pairs (2,3), (4,5)… = 0-based (1,2), (3,4)…
 *   (`contracts/responsive-strategy.md` §6 rule 4); false 时第 0 页与第 1 页
 *   并摊（0-based (0,1), (2,3)…），换算经 PageMode 带参 helper 与
 *   ReaderView 翻页共用。
 * - RTL reading direction reverses the spread: the reading-order-first page
 *   sits on the RIGHT (`SpreadLayoutManager.SPREAD_RIGHT_TO_LEFT`).
 * - splitWidePages（reader 偏好）：开启时宽幅页（load 后量得宽高比 > 1.2）
 *   在槽位内拆成左右两半并排显示（overflow:hidden 半容器 + 宽 200% 的
 *   img 偏移 0/-100%），页码/进度语义不变；单页/滚动模式不拆。
 * - Navigation moves by whole spreads, like the Android pager.
 * - Per responsive-strategy §8, each page requests `?w=` at HALF the
 *   container width (× DPR).
 * - Triggered by `orientation: landscape` / `min-aspect-ratio: 1/1`
 *   (resolved by the parent — see §6 rules 1–3).
 *
 * Zoom intentionally applies to nothing here: per the original design
 * decision, dual-page spreads are viewed at fit-width (no per-page zoom).
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import ProgressSpinner from '@/components/atoms/ProgressSpinner.vue'
import { usePreferencesStore } from '@/stores/preferences'
import {
  firstPageOfSpread,
  pageImageSrcset,
  pageImageUrl,
  spreadIndexOf,
  useReaderGestures,
} from './PageMode.vue'
import type { HorizontalDirection } from './PageMode.vue'

interface DualPageModeProps {
  gid: number
  /** 0-based index of the current (reading-order first visible) page. */
  page: number
  totalPages: number
  direction: HorizontalDirection
  /** AI-enhanced hot-swap URLs keyed by 0-based page. */
  enhancedUrls?: ReadonlyMap<number, string>
}

interface DualPageModeEmits {
  /** Previous / next SPREAD (reading order). */
  (e: 'prev'): void
  (e: 'next'): void
  (e: 'toggle-chrome'): void
}

const props = withDefaults(defineProps<DualPageModeProps>(), {
  enhancedUrls: undefined,
})
const emit = defineEmits<DualPageModeEmits>()

const preferencesStore = usePreferencesStore()

/* firstPageCover / splitWidePages 偏好（活消费；prefs 未载入时回退默认）。
 * 直接读 store 与 PageMode 的 pageScaling/tapZoneScheme 同模式——比经
 * ImageReader 层层透传 props 改动面更小，且设置改动即时生效。 */
const firstPageCover = computed(() => preferencesStore.prefs?.reader.firstPageCover ?? true)
const splitWidePages = computed(() => preferencesStore.prefs?.reader.splitWidePages ?? false)

const rootRef = ref<HTMLElement | null>(null)
const rootWidth = ref(800)
const enterClass = ref('')
const loadedPages = reactive(new Set<number>())

let rootObserver: ResizeObserver | null = null

function devicePixelRatio(): number {
  return typeof window !== 'undefined' && window.devicePixelRatio > 0
    ? window.devicePixelRatio
    : 1
}

onMounted(() => {
  const el = rootRef.value
  if (!el) return
  rootWidth.value = el.clientWidth || window.innerWidth
  if (typeof ResizeObserver !== 'undefined') {
    rootObserver = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width
      if (width && width > 0) rootWidth.value = width
    })
    rootObserver.observe(el)
  }
})

onBeforeUnmount(() => {
  rootObserver?.disconnect()
})

/* ------------------------------------------------------------------ */
/* Spread resolution — cover alone, then (1,2), (3,4), …               */
/* ------------------------------------------------------------------ */

const spread = computed(() => {
  const total = props.totalPages
  const index = Math.min(Math.max(props.page, 0), Math.max(0, total - 1))
  // firstPageCover=false：第 0 页与第 1 页并摊，铺摊边界整体前移一位
  // （(0,1), (2,3)…）；换算与 ReaderView 翻页共用同一带参 helper。
  const first = firstPageCover.value
    ? index === 0
      ? 0
      : firstPageOfSpread(spreadIndexOf(index))
    : firstPageOfSpread(spreadIndexOf(index, false), false)
  const second = first + 1 < total ? first + 1 : null
  const rtl = props.direction === 'rtl'
  return {
    first,
    /** Physically left slot (reading-order second page in RTL). */
    left: second === null ? first : rtl ? second : first,
    /** Physically right slot (null when the page stands alone). */
    right: second === null ? first : rtl ? first : second,
    alone: second === null,
  }
})

const spreadLabel = computed(() =>
  spread.value.alone
    ? `Page ${spread.value.first + 1} of ${props.totalPages}`
    : `Pages ${spread.value.first + 1}–${spread.value.first + 2} of ${props.totalPages}`,
)

/* ------------------------------------------------------------------ */
/* Responsive URLs — each page gets half the container width (§8)      */
/* ------------------------------------------------------------------ */

function srcFor(page: number): string {
  const enhanced = props.enhancedUrls?.get(page)
  if (enhanced) return enhanced
  return pageImageUrl(props.gid, page, (rootWidth.value / 2) * devicePixelRatio())
}

function srcsetFor(page: number): string | undefined {
  if (props.enhancedUrls?.get(page)) return undefined
  return pageImageSrcset(props.gid, page, rootWidth.value / 2)
}

/* 拆分页横跨整行显示（= 整页宽度），按整行宽取图，避免半宽图放大发虚。 */
function srcForFull(page: number): string {
  const enhanced = props.enhancedUrls?.get(page)
  if (enhanced) return enhanced
  return pageImageUrl(props.gid, page, rootWidth.value * devicePixelRatio())
}

function srcsetForFull(page: number): string | undefined {
  if (props.enhancedUrls?.get(page)) return undefined
  return pageImageSrcset(props.gid, page, rootWidth.value)
}

/* ------------------------------------------------------------------ */
/* splitWidePages — 宽幅页拆左右两半（双页模式限定）                     */
/* ------------------------------------------------------------------ */

/** 宽幅判定阈值：宽高比超过它才拆（协议定案值）。 */
const SPLIT_WIDE_RATIO = 1.2
const SPLIT_HALVES = ['left', 'right'] as const

/** 页面宽高比缓存：普通渲染 img load 时量 naturalWidth/Height（首次为准，
 *  增强 hot-swap 换图不重测——罕见且重测会引起布局跳变）。 */
const pageRatios = reactive(new Map<number, number>())

/** 该页是否按拆分渲染：偏好开 + 已量得比例且超阈值。 */
function isSplit(page: number): boolean {
  const ratio = pageRatios.get(page)
  return splitWidePages.value && ratio !== undefined && ratio > SPLIT_WIDE_RATIO
}

/** 拆分容器的整页 aspect-ratio（两半 = 整页，保证中缝对齐不失真）。 */
function splitAspect(page: number): string {
  return String(pageRatios.get(page) ?? SPLIT_WIDE_RATIO)
}

function onPageLoad(page: number, event?: Event) {
  const img = event?.target as HTMLImageElement | undefined
  if (img?.naturalWidth && img?.naturalHeight) {
    pageRatios.set(page, img.naturalWidth / img.naturalHeight)
  }
  loadedPages.add(page)
}

/* ------------------------------------------------------------------ */
/* Spread-turn enter animation                                         */
/* ------------------------------------------------------------------ */

watch(
  () => spread.value.first,
  (next, prev) => {
    if (prev === undefined) return
    const forward = next > prev
    const fromRight = props.direction === 'rtl' ? !forward : forward
    enterClass.value = fromRight
      ? 'dual-page__row--from-right'
      : 'dual-page__row--from-left'
  },
)

/* ------------------------------------------------------------------ */
/* Gestures — same tap zones / swipe rules as single-page mode         */
/* ------------------------------------------------------------------ */

useReaderGestures({
  el: rootRef,
  isRtl: () => props.direction === 'rtl',
  suppressed: () => false,
  onPrev: () => emit('prev'),
  onNext: () => emit('next'),
  onToggleChrome: () => emit('toggle-chrome'),
})
</script>

<style scoped>
.dual-page {
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
}

/* --- A4: edge hot-zone hints (same affordance as PageMode) ------------- */

.dual-page__hint {
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

.dual-page__hint svg {
  width: 28px;
  height: 28px;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.6));
}

.dual-page__hint--prev {
  left: 0;
  justify-content: flex-start;
  padding-left: 12px;
  cursor: w-resize;
  background: linear-gradient(to right, rgba(0, 0, 0, 0.4), transparent);
}

.dual-page__hint--next {
  right: 0;
  justify-content: flex-end;
  padding-right: 12px;
  cursor: e-resize;
  background: linear-gradient(to left, rgba(0, 0, 0, 0.4), transparent);
}

.dual-page__hint:hover {
  opacity: 1;
}

/* Touch / hover-less devices never see the hints. */
@media (hover: none), (pointer: coarse) {
  .dual-page__hint {
    display: none;
  }
}

.dual-page__row {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  /* reader.dualPageGap 偏好：变量由 ReaderView 写在阅读器根元素上，经 CSS
   * 自定义属性继承到这里（决策表兜底 fallback 8px 仅防根元素缺席）。
   * RTL 无需镜像——列宽对称，间隙居中不变。 */
  column-gap: var(--reader-dual-gap, 8px);
  align-items: center;
  width: 100%;
  height: 100%;
}

.dual-page__row--from-right {
  animation: dual-page-enter-right var(--duration-scene-translate)
    var(--ease-decelerate-quint);
}

.dual-page__row--from-left {
  animation: dual-page-enter-left var(--duration-scene-translate)
    var(--ease-decelerate-quint);
}

/* Subtle book-spine shading between the two pages of a paired spread. */
.dual-page__row:not(:has(.dual-page__slot--alone))::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 8%;
  bottom: 8%;
  width: 2px;
  transform: translateX(-50%);
  background: linear-gradient(
    to bottom,
    transparent,
    color-mix(in srgb, var(--color-black) 45%, transparent) 18%,
    color-mix(in srgb, var(--color-black) 45%, transparent) 82%,
    transparent
  );
  pointer-events: none;
}

.dual-page__slot {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  height: 100%;
}

.dual-page__slot--alone {
  grid-column: 1 / -1;
}

/* 铺满槽位（contain 可放大可缩小）——max-* 只缩不放会让原图小于
   槽位的平板出现大片黑边。封面独页（--alone）保持"半幅居中"语义。 */
.dual-page__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  opacity: 0;
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

/* A lone page reads as one column of the spread, centered. */
.dual-page__img--alone {
  width: 50%;
}

.dual-page__img--loaded {
  opacity: 1;
}

/*
 * splitWidePages 拆分渲染：外层按整页比例（aspect-ratio 由内联样式按实测
 * naturalWidth/Height 给出），两个 overflow:hidden 半容器各占一半；内部 img
 * 宽 200%、右半再偏移 -100%（= 一个半容器宽），恰好拼回整页且中缝对齐。
 * max-width 钳到槽位时的极端情况由 object-fit: contain 兜底（不裁切变形）。
 */
.dual-page__split {
  display: flex;
  height: 100%;
  max-width: 100%;
  margin: 0 auto;
}

.dual-page__half {
  position: relative;
  width: 50%;
  height: 100%;
  overflow: hidden;
}

.dual-page__split-img {
  display: block;
  width: 200%;
  height: 100%;
  max-width: none;
  object-fit: contain;
  opacity: 0;
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dual-page__half--right .dual-page__split-img {
  margin-left: -100%;
}

.dual-page__split-img--loaded {
  opacity: 1;
}

.dual-page__loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dual-page-fade-enter-active,
.dual-page-fade-leave-active {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dual-page-fade-enter-from,
.dual-page-fade-leave-to {
  opacity: 0;
}

@keyframes dual-page-enter-right {
  from {
    transform: translateX(6%);
    opacity: 0.3;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

@keyframes dual-page-enter-left {
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
  .dual-page__row--from-right,
  .dual-page__row--from-left {
    animation: none;
  }
}
</style>
