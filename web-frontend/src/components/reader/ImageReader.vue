<template>
  <!--
    pointermove (mouse only) wakes the chrome and re-arms its idle countdown
    (plan-2026-09-05 A6); throttled, and touch never synthesizes it here.
  -->
  <div ref="rootRef" class="image-reader" @pointermove="onChromePointerMove">
    <!--
      Page area — fills the viewport behind the overlaid chrome.
      A6: 亮度 101–200 的提亮段以 CSS filter 施加于此容器（只作用于页面
      内容，chrome 与遮罩不参与；App 端该段是真背光增强，Web 以 filter
      近似）。容器恒为 absolute inset 0，filter 的有无不改变子组件几何。
    -->
    <div class="image-reader__pages" :style="pagesFilterStyle">
      <PageMode
        v-if="mode === 'page'"
        :gid="gid"
        :page="currentPage"
        :total-pages="totalPages"
        :direction="direction === 'rtl' ? 'rtl' : 'ltr'"
        :zoom="zoom"
        :enhanced-urls="enhancedUrls"
        @update:zoom="(value) => emit('update:zoom', value)"
        @prev="emit('prev')"
        @next="emit('next')"
        @toggle-chrome="toggleChrome"
      />
      <DualPageMode
        v-else-if="mode === 'dual'"
        :gid="gid"
        :page="currentPage"
        :total-pages="totalPages"
        :direction="direction === 'rtl' ? 'rtl' : 'ltr'"
        :enhanced-urls="enhancedUrls"
        @prev="emit('prev')"
        @next="emit('next')"
        @toggle-chrome="toggleChrome"
      />
      <ScrollMode
        v-else
        :gid="gid"
        :total-pages="totalPages"
        :current-page="currentPage"
        :scrubbing="seeking"
        :enhanced-urls="enhancedUrls"
        @update:current-page="(page) => emit('update:currentPage', page)"
        @toggle-chrome="toggleChrome"
      />
    </div>

    <!--
      GalleryHeader replica (clock / stroked N/M progress / battery),
      docked just under the toolbar; auto-hides after 3 s idle
      (HIDE_SLIDER_DELAY) — the bar only NOTIFIES the idle timeout (A6) and
      this component decides whether to hide (chrome hover pauses it).
    -->
    <div class="image-reader__status-bar" :aria-hidden="!chromeVisible">
      <!-- :key remount re-arms the bar's internal HIDE_SLIDER_DELAY countdown
           after interactions the idle guard swallowed (scrub, settings). -->
      <ReaderStatusBar
        ref="statusBarRef"
        :key="statusBarEpoch"
        :current-page="currentPage + 1"
        :total-pages="totalPages"
        :visible="chromeVisible"
        :show-progress="showProgressPref"
        @idle="onStatusBarIdle"
      />
    </div>

    <ReaderToolbar
      :visible="chromeVisible"
      :title="title"
      :rtl="direction === 'rtl'"
      @back="emit('back')"
      @open-settings="settingsVisible = true"
    />

    <!-- SeekBarPanel (activity_gallery.xml bottom panel), 1-based contract -->
    <div
      class="image-reader__seekbar"
      :class="{ 'image-reader__seekbar--hidden': !chromeVisible }"
      :aria-hidden="!chromeVisible"
    >
      <SeekBarPanel
        :current-page="currentPage + 1"
        :total-pages="totalPages"
        :reversed="direction === 'rtl'"
        :show-interval-ticks="showIntervalTicks"
        @change="onSeekCommit"
        @update:current-page="onSeekPreview"
        @seek-start="onSeekStart"
        @seek-end="onSeekEnd"
      />
    </div>

    <!-- auto_transfer indicator: countdown ring, tap to stop -->
    <button
      v-if="autoPlay.enabled"
      type="button"
      class="image-reader__autoplay"
      :aria-label="`Auto-play every ${autoPlay.intervalMs / 1000} seconds — activate to stop`"
      @click="stopAutoPlay"
    >
      <ProgressSpinner
        size="small"
        :indeterminate="false"
        :progress="autoPlayProgress"
        color="var(--color-white)"
      />
      <span class="image-reader__autoplay-label">{{ autoPlay.intervalMs / 1000 }}s</span>
    </button>

    <ReaderSettings
      :visible="settingsVisible"
      :direction="direction"
      :page-mode="pageMode"
      :zoom="zoom"
      :auto-play="autoPlay"
      :brightness-level="brightnessLevel"
      :wake-lock="wakeLock"
      :orientation-lock="orientationLock"
      :current-page="currentPage + 1"
      :total-pages="totalPages"
      @close="closeSettings"
      @jump="openJumpDialog"
      @update:direction="(value) => emit('update:direction', value)"
      @update:page-mode="(value) => emit('update:pageMode', value)"
      @update:zoom="(value) => emit('update:zoom', value)"
      @update:auto-play="(value) => emit('update:autoPlay', value)"
      @update:brightness-level="(value) => emit('update:brightnessLevel', value)"
      @update:wake-lock="(value) => emit('update:wakeLock', value)"
      @update:orientation-lock="(value) => emit('update:orientationLock', value)"
    />

    <!-- A5: 跳页对话框（设置面板菜单入口 + G 键）；确定走 SeekBarPanel
         同一 seek 通路（update:currentPage），双页铺摊语义由既有换算保证 -->
    <PageJumpDialog
      :visible="jumpDialogVisible"
      :current-page="currentPage"
      :total-pages="totalPages"
      @close="closeJumpDialog"
      @jump="onJumpCommit"
    />

    <!-- Brightness mask — the activity_gallery.xml `mask` ColorView -->
    <div class="image-reader__mask" :style="{ opacity: maskOpacity }" aria-hidden="true" />

    <!-- Android 返回手势的边缘指示条（触屏边缘向内拖动退出阅读器） -->
    <div
      v-if="edgeBack.side"
      class="image-reader__edge-back"
      :class="`image-reader__edge-back--${edgeBack.side}`"
      :style="{ width: `${edgeBack.progress * 28}px` }"
      aria-hidden="true"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * ImageReader.vue — the reader chrome hub, composing the frozen contract
 * components exactly like `activity_gallery.xml` + `GalleryActivity`:
 *
 * - `GalleryHeader` (ReaderStatusBar) floats over the page and auto-hides
 *   after `HIDE_SLIDER_DELAY` (3 s) of idle — re-armed on every interaction,
 *   mouse movement (A6) or chrome tap; the bar only notifies the idle
 *   timeout and this component decides (chrome hover pauses the hide).
 * - `SeekBarPanel` + `ReversibleSeekBar` dock at the bottom (48dp,
 *   `gallerySliderBackgroundColor`), mirrored for RTL reading; the page jump
 *   applies on release (`change`), while labels track the drag live. In
 *   scroll mode the drag also previews instantly (A8).
 * - Brightness (A6): 设备本地亮度 0–200。0–100 是 `mask` ColorView 语义：
 *   黑色遮罩，不透明度随压暗等级变化（0 = 跟随系统）；101–200 提亮段不加
 *   遮罩，以页面容器的 CSS filter 近似 App 端 lp.screenBrightness 真背光
 *   增强——跨端同步值恒写 clamp(v, 0, 100)，红线在 ReaderView.writeSettings。
 * - Page jump (A5): 跳页对话框（PageJumpDialog，ReaderSettings 菜单入口 +
 *   G 快捷键），确认与 SeekBarPanel 松手走完全相同的 seek 通路。
 * - Orientation lock (A7): 全屏期间按设备本地偏好 `screen.orientation.lock`，
 *   退全屏/卸载必解锁（含在途请求兜底），被拒静默降级。
 * - Auto-play mirrors `auto_transfer`: a countdown chip above the seek bar.
 * - Adjacent pages are preloaded (next 2 / prev 1) for zero-wait turns, at
 *   the same responsive `?w=` width the page components request.
 * - Wake Lock (T1b): while reading, a `screen` wake lock keeps the display on
 *   (Android keep-screen-on parity); the device-local switch lives in the
 *   settings sheet, unsupported platforms degrade silently.
 * - Real fullscreen (T1c): `reader.fullscreen` upgrades from "pre-hide the
 *   chrome" to the Fullscreen API on this root element, synced via
 *   `fullscreenchange` (user Esc keeps state consistent); rejection falls
 *   back to the pre-hide behavior.
 *
 * Page navigation itself is owned by the parent (ReaderView): this component
 * re-emits semantic `prev` / `next` (spread-awareness included) and direct
 * `update:currentPage` jumps from the seek bar.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ProgressSpinner from '@/components/atoms/ProgressSpinner.vue'
import { useEdgeBackGesture } from '@/composables/useEdgeBackGesture'
import { usePreferencesStore } from '@/stores/preferences'

const preferencesStore = usePreferencesStore()
import ReaderStatusBar from './ReaderStatusBar.vue'
import SeekBarPanel from './SeekBarPanel.vue'
import ReaderToolbar from './ReaderToolbar.vue'
import ReaderSettings from './ReaderSettings.vue'
import PageJumpDialog from './PageJumpDialog.vue'
import PageMode from './PageMode.vue'
import DualPageMode from './DualPageMode.vue'
import ScrollMode from './ScrollMode.vue'
import { pageImageUrl } from './PageMode.vue'
import type { OrientationLockPref } from './ReaderSettings.vue'
import type {
  AutoPlayState,
  PageModePref,
  ReadingDirection,
  ResolvedReaderMode,
} from './PageMode.vue'

interface ImageReaderProps {
  gid: number
  title: string
  totalPages: number
  /** 0-based current page. v-model:currentPage. */
  currentPage: number
  /** Reading direction setting. v-model:direction. */
  direction: ReadingDirection
  /** User page-mode preference (settings). v-model:pageMode. */
  pageMode: PageModePref
  /** Resolved mode after `auto` + direction (parent owns the viewport watch). */
  mode: ResolvedReaderMode
  /** Zoom factor for page mode. v-model:zoom. */
  zoom: number
  /**
   * A6: 设备本地亮度等级 0–200（ReaderView 的 reader-settings localStorage
   * 键 `brightnessLevel`，不进服务器同步）。0 = 跟随系统；1–100 压暗（遮罩
   * (1 - v/100) * 0.87，与一期完全一致）；101–200 提亮（页面容器 CSS
   * filter: brightness(1 + (v-100)/100)，不加遮罩——App 端该段是真背光增强
   * GalleryActivity.setScreenLightness，Web 以 filter 近似）。跨端同步的
   * prefs.reader.brightness 只写 clamp(v, 0, 100)（红线在 ReaderView）。
   * v-model:brightnessLevel。
   */
  brightnessLevel: number
  /** Auto-play state. v-model:autoPlay. */
  autoPlay: AutoPlayState
  /** Countdown progress of the current auto-play tick, 0–1. */
  autoPlayProgress: number
  /**
   * T1b: 设备本地的屏幕常亮开关（ReaderView 的 reader-settings localStorage，
   * 不进服务器同步）。v-model:wakeLock。
   */
  wakeLock: boolean
  /**
   * A7: 屏幕方向锁定（ReaderSettings 面板三态选择，设备本地偏好，不进服务
   * 器同步）。仅全屏时尝试 screen.orientation.lock；被拒静默降级。
   */
  orientationLock: OrientationLockPref
  /** AI-enhanced hot-swap URLs keyed by 0-based page. */
  enhancedUrls?: ReadonlyMap<number, string>
}

interface ImageReaderEmits {
  (e: 'update:currentPage', page: number): void
  (e: 'update:direction', direction: ReadingDirection): void
  (e: 'update:pageMode', mode: PageModePref): void
  (e: 'update:zoom', zoom: number): void
  (e: 'update:brightnessLevel', brightnessLevel: number): void
  (e: 'update:autoPlay', state: AutoPlayState): void
  (e: 'update:wakeLock', enabled: boolean): void
  (e: 'update:orientationLock', lock: OrientationLockPref): void
  /** Semantic navigation — the parent maps to page indices (spread-aware). */
  (e: 'prev'): void
  (e: 'next'): void
  (e: 'back'): void
}

const props = withDefaults(defineProps<ImageReaderProps>(), {
  enhancedUrls: undefined,
})
const emit = defineEmits<ImageReaderEmits>()

const rootRef = ref<HTMLElement | null>(null)
const rootWidth = ref(0)
/** The status bar instance — its idle countdown is re-armed on mouse wake. */
const statusBarRef = ref<InstanceType<typeof ReaderStatusBar> | null>(null)

/**
 * Chrome (status bar + toolbar + seek bar) visibility — tap to toggle.
 * 初值接 reader.fullscreen 偏好（true = 进阅读器即全屏、chrome 藏起）；
 * 三端统一默认 true（Wave-2 T2 定案，与服务端 PreferenceDto、App Settings
 * 同值）。T1c：偏好同时驱动真全屏（Fullscreen API，见下方 T1c 段）；请求被拒
 * 时本预隐藏行为即回退态，退出/切页/tap 切换语义不变。
 */
const chromeVisible = ref(!(preferencesStore.prefs?.reader.fullscreen ?? true))
const settingsVisible = ref(false)
/** True while the seek bar is being scrubbed. */
const seeking = ref(false)
/** Bumped to remount ReaderStatusBar and restart its idle countdown. */
const statusBarEpoch = ref(0)

/* ------------------------------------------------------------------ */
/* Wave-1 偏好活消费：showProgress / showPageInterval                   */
/* ------------------------------------------------------------------ */

/** reader.showProgress：只隐藏状态栏的页码行，其余 chrome 不受影响。 */
const showProgressPref = computed(() => preferencesStore.prefs?.reader.showProgress ?? true)

/** 页间隔刻度：分页模式 + 偏好开启才渲染（滚动模式不显示；页数≤2 由面板内再兜）。 */
const showIntervalTicks = computed(
  () => props.mode !== 'scroll' && (preferencesStore.prefs?.reader.showPageInterval ?? true),
)

/* ------------------------------------------------------------------ */
/* Back / exit — Android 系统返回语义                                    */
/* ------------------------------------------------------------------ */

/**
 * Esc（键盘）与触屏边缘向内滑动（useEdgeBackGesture）共用的返回入口，
 * 语义对齐 Android 端「返回键退出阅读器」：跳页对话框/设置面板开着先关
 * 面板，否则向上抛 back 由父级退出（history back 优先）。
 */
function handleBack() {
  if (jumpDialogVisible.value) {
    closeJumpDialog()
    return
  }
  if (settingsVisible.value) {
    closeSettings()
    return
  }
  emit('back')
}

// 触屏边缘手势只挂在页面区（rootRef），落在 chrome 上的触点不受影响。
const { state: edgeBack } = useEdgeBackGesture(rootRef, { onBack: handleBack })

/* ------------------------------------------------------------------ */
/* Chrome toggling + GalleryHeader idle interplay                      */
/* ------------------------------------------------------------------ */

function toggleChrome() {
  chromeVisible.value = !chromeVisible.value
}

/* ------------------------------------------------------------------ */
/* A6: chrome visibility — mouse wake + centralized idle decision      */
/* ------------------------------------------------------------------ */

/**
 * The status bar only NOTIFIES when its 3s idle countdown fires; hiding (or
 * pausing) is decided here, so a pointer parked on the interactive chrome
 * suspends the auto-hide for as long as it stays there.
 */
function onStatusBarIdle() {
  // The 3 s idle timeout must not fire mid-scrub or while the settings
  // sheet is open — GalleryActivity likewise keeps the slider while
  // tracking; seek-end / closing the sheet re-arms the countdown.
  if (seeking.value || settingsVisible.value) return
  if (pointerOnChrome()) {
    // Hovering the toolbar / seek bar pauses the self-hide: re-arm instead.
    statusBarRef.value?.resetIdle()
    return
  }
  chromeVisible.value = false
}

/** Last (throttled) mouse position — -1 until a real mouse move is seen. */
const lastMouse = { x: -1, y: -1 }
let lastPointerMoveAt = 0

/** A6: mouse wake — throttle keeps the handler ~free (200ms ceiling). */
const CHROME_WAKE_THROTTLE_MS = 200

function onChromePointerMove(event: PointerEvent) {
  // Touch/pen contact also fires pointermove — only the mouse wakes chrome.
  if (event.pointerType !== 'mouse') return
  const now = Date.now()
  if (now - lastPointerMoveAt < CHROME_WAKE_THROTTLE_MS) return
  lastPointerMoveAt = now
  lastMouse.x = event.clientX
  lastMouse.y = event.clientY
  if (!chromeVisible.value) {
    // Showing the bar re-arms its idle countdown via the visible watcher.
    chromeVisible.value = true
  } else {
    statusBarRef.value?.resetIdle()
  }
}

/**
 * Is the mouse currently resting on the interactive chrome (toolbar / seek
 * bar)? The status bar itself is pointer-events:none (taps pass through to
 * the reader), so it deliberately never counts as a hover anchor.
 */
function pointerOnChrome(): boolean {
  if (lastMouse.x < 0) return false
  if (typeof document === 'undefined' || typeof document.elementFromPoint !== 'function') {
    return false
  }
  const el = document.elementFromPoint(lastMouse.x, lastMouse.y)
  return el?.closest('.reader-toolbar, .image-reader__seekbar') != null
}

function onSeekStart() {
  seeking.value = true
  chromeVisible.value = true
}

function onSeekEnd() {
  seeking.value = false
  // Re-arm the header's idle countdown — mirrors GalleryActivity re-posting
  // HIDE_SLIDER_DELAY in onStopTrackingTouch. The guard in
  // onStatusBarIdle swallowed the mid-scrub timeout, so remount the
  // bar to restart its countdown without a visibility flicker.
  if (chromeVisible.value) statusBarEpoch.value += 1
}

function closeSettings() {
  settingsVisible.value = false
  // Same re-arm as after a scrub: the idle timeout that fired while the
  // sheet was open was ignored, so restart the countdown now.
  if (chromeVisible.value) statusBarEpoch.value += 1
}

/* ------------------------------------------------------------------ */
/* A5 — 跳页对话框（ReaderSettings 菜单入口 + G 键）                     */
/* ------------------------------------------------------------------ */

const jumpDialogVisible = ref(false)

/** 打开跳页：设置面板先收起、chrome 唤出（对话框与 chrome 同屏）。 */
function openJumpDialog(): void {
  if (props.totalPages <= 0) return // 未知页数（import .db）无跳页语义
  settingsVisible.value = false
  chromeVisible.value = true
  jumpDialogVisible.value = true
}

function closeJumpDialog(): void {
  jumpDialogVisible.value = false
  // 与 closeSettings 同一 re-arm：对话框打开期间被搁置的 idle 倒计时重来。
  if (chromeVisible.value) statusBarEpoch.value += 1
}

/**
 * 对话框确定（1-based 页号，已由对话框钳入 [1, totalPages]）→ 与滑杆松手
 * （SeekBarPanel `change` → onSeekCommit）完全同一 seek 通路；双页模式下
 * 「跳到的页即铺摊主页」由既有的渲染换算自动保证。
 */
function onJumpCommit(page: number): void {
  emit('update:currentPage', page - 1)
  closeJumpDialog()
}

/**
 * G 键快捷键。useKeyboardNav（ReaderView 侧，冻结文件）不认领单字母 G，
 * 这里在阅读器侧自挂 window keydown；守卫与 useKeyboardNav 同一套（修饰键/
 * IME/表单焦点放行），对话框输入框里打字不会被劫持。
 */
function onReaderKeyDown(event: KeyboardEvent): void {
  if (event.ctrlKey || event.metaKey || event.altKey) return
  if (event.isComposing) return
  const target = event.target
  if (
    target instanceof HTMLElement &&
    (target.isContentEditable ||
      target.tagName === 'INPUT' ||
      target.tagName === 'TEXTAREA' ||
      target.tagName === 'SELECT')
  ) {
    return
  }
  if (event.key.length === 1 && event.key.toLowerCase() === 'g') {
    event.preventDefault()
    openJumpDialog()
  }
}

/** Seek bar release — the page jump applies here (1-based contract). */
function onSeekCommit(page: number) {
  emit('update:currentPage', page - 1)
}

/**
 * A8: scrub preview — the seek bar reports live input positions while
 * dragging. Scroll mode jumps instantly (no smooth animation fighting the
 * finger); page/dual modes keep the release-commit contract and ignore
 * intermediate positions. The round-trip keeps the existing progress
 * semantics (ReaderView throttles the writeback as for any page change).
 */
function onSeekPreview(page: number) {
  if (props.mode !== 'scroll') return
  emit('update:currentPage', page - 1)
}

function stopAutoPlay() {
  emit('update:autoPlay', { enabled: false, intervalMs: props.autoPlay.intervalMs })
}

/* ------------------------------------------------------------------ */
/* Brightness (A6) — 0–100 遮罩压暗 + 101–200 filter 提亮               */
/* ------------------------------------------------------------------ */

/**
 * 一期语义原样保留（Wave-2 T2 定案）：≤100 为压暗等级（0 = 跟随系统），
 * 遮罩不透明度 = (1 - v/100) * 0.87——系数与 App 端遮罩 alpha 0xde/255
 * ≈ 0.87 三端统一。101–200 段不加遮罩（App 端该段 mMaskView.setColor(0)）。
 */
const dimmingLevel = computed(() => Math.min(props.brightnessLevel, 100))

const maskOpacity = computed(() =>
  dimmingLevel.value <= 0 ? 0 : (1 - dimmingLevel.value / 100) * 0.87,
)

/**
 * 提亮段（101–200）：对页面内容容器加 CSS filter（200 → 2×），chrome 与
 * 遮罩都不参与。定性说明：App 端同段是 lp.screenBrightness 真背光增强
 * （设备本地，GalleryActivity.setScreenLightness 的 lightness 0–200），Web
 * 无法直接驱动背光，以 filter 近似——因此跨端同步值恒写 clamp(v, 0, 100)
 * （用户拉到 >100 时同步值停在 100，App 端把 100 解释为「无压暗」，正确），
 * 从不把 >100 写进 synced store。红线落实在 ReaderView.writeSettings。
 */
const pagesFilterStyle = computed<{ filter: string } | undefined>(() =>
  props.brightnessLevel > 100
    ? { filter: `brightness(${1 + (props.brightnessLevel - 100) / 100})` }
    : undefined,
)

/* ------------------------------------------------------------------ */
/* T1b — Wake Lock：阅读期间屏幕常亮（Android keep-screen-on 对齐）      */
/* ------------------------------------------------------------------ */

/**
 * 设备本地开关（props.wakeLock，落 ReaderView 的 reader-settings
 * localStorage，不进服务器同步）。阅读器挂载即获取 `screen` wake lock；
 * 页面隐藏时浏览器会自动释放（这里主动 release 同步本地引用，幂等），
 * 回到可见重新获取；卸载释放。不支持的平台（iOS Safari 等）静默降级。
 */
const wakeLockSentinel = ref<WakeLockSentinel | null>(null)

async function acquireWakeLock(): Promise<void> {
  if (!props.wakeLock || wakeLockSentinel.value) return
  // 隐藏文档里 request('screen') 必被拒——跳过，等 visibilitychange→visible 再取。
  if (typeof document !== 'undefined' && document.hidden) return
  // 特性检测：lib.dom 类型视其为必有，不支持运行时上是 undefined。
  if (typeof navigator === 'undefined' || !navigator.wakeLock) return
  try {
    wakeLockSentinel.value = await navigator.wakeLock.request('screen')
  } catch {
    // 被拒（无手势/低电量模式/不支持）——静默降级，阅读不受影响。
  }
}

async function releaseWakeLock(): Promise<void> {
  const sentinel = wakeLockSentinel.value
  wakeLockSentinel.value = null
  if (!sentinel || sentinel.released) return
  try {
    await sentinel.release()
  } catch {
    // 浏览器已随文档隐藏释放——幂等清理。
  }
}

function onReaderVisibilityChange(): void {
  if (document.hidden) {
    void releaseWakeLock()
  } else {
    void acquireWakeLock()
  }
}

watch(
  () => props.wakeLock,
  (enabled) => {
    if (enabled) void acquireWakeLock()
    else void releaseWakeLock()
  },
)

/* ------------------------------------------------------------------ */
/* T1c — 真全屏：Fullscreen API 接管 reader.fullscreen 偏好              */
/* ------------------------------------------------------------------ */

/**
 * `fullscreenchange` 是唯一事实源：请求成功、用户 Esc 退出、浏览器强制退出
 * 都经它同步，内部状态不会漂移（Esc 退出后 UI 照常：chrome 仍按预隐藏语义
 * 由 tap/鼠标唤醒切换）。`requestFullscreen()` 在进入阅读器的挂载流程中调用
 * ——源自打开阅读器的用户手势（瞬态激活窗口内；极慢的加载会过期失活），
 * 被拒则静默回退现状「预隐藏 chrome」行为（chromeVisible 初值已生效）。
 */
const isFullscreen = ref(false)

function onFullscreenChange(): void {
  isFullscreen.value = document.fullscreenElement != null
  // A7: 方向锁跟随全屏生命周期——进全屏（重新）上锁，退全屏解锁。
  if (isFullscreen.value) void applyOrientationLock()
  else releaseOrientationLock()
}

/** 进入阅读器流程中对阅读器根元素请求全屏（用户手势调用链内）。 */
async function enterFullscreen(): Promise<void> {
  const el = rootRef.value
  if (!el || isFullscreen.value || document.fullscreenElement != null) return
  if (typeof el.requestFullscreen !== 'function') return // iOS Safari 等
  try {
    await el.requestFullscreen()
  } catch {
    // 无手势 / 不支持 —— 静默回退预隐藏 chrome（现状行为）。
  }
}

/** 只退出自己进入的全屏（本组件根元素），不碰他处或用户手动的全屏状态。 */
function exitReaderFullscreen(): void {
  if (rootRef.value && document.fullscreenElement === rootRef.value) {
    void document.exitFullscreen().catch(() => {
      // 已不在全屏（如浏览器先行退出）——无可还原状态，忽略。
    })
  }
}

// 偏好驱动真全屏：关 → 立即退出，回到预隐藏语义；开 → 补一次进入。
// 深链直进阅读器时挂载早于 prefs 加载，onMounted 的 enterFullscreen 被跳过
// ——prefs 从 null→加载完成这一跳（undefined→true）同样落在 watch 里，在此
// 补进真全屏（此时已不保证还在手势瞬态激活窗口内，被拒则静默回退预隐藏
// chrome，与挂载路径同一守卫）。
watch(
  () => preferencesStore.prefs?.reader.fullscreen,
  (enabled) => {
    if (enabled) void enterFullscreen()
    else exitReaderFullscreen()
  },
)

/* ------------------------------------------------------------------ */
/* A7 — 屏幕方向锁定（设备本地偏好；仅全屏尝试，被拒静默降级）           */
/* ------------------------------------------------------------------ */

/**
 * Web 无法像 Android 一样 setRequestedOrientation——`screen.orientation.lock`
 * 只在全屏文档上受支持（iOS Safari / 非全屏环境直接 reject）。策略：
 * - 锁定跟随 isFullscreen 生命周期：进全屏（重新）上锁，退全屏解锁；
 * - 卸载兜底解锁（含请求仍在途、迟到兑现的情况）——方向锁绝不过期滞留
 *   （「解锁泄漏」红线）；
 * - lock 被拒：静默降级，偏好本身照常保留（仅本机不生效）。
 * `OrientationLockPref`（none/portrait/landscape）定义在 ReaderSettings.vue
 * （面板是偏好入口，ImageReader 经既有依赖引入，避免反向环）。
 */
type LockableOrientation = Exclude<OrientationLockPref, 'none'>

/** 最小接口声明：lib.dom 的 ScreenOrientation.lock 类型随 TS 版本漂移，
 *  统一收窄到本地最小面 + 运行时特性检测。 */
interface LockableScreenOrientation {
  lock(orientation: LockableOrientation): Promise<void>
  unlock(): void
}

function lockableScreenOrientation(): LockableScreenOrientation | undefined {
  if (typeof screen === 'undefined') return undefined
  const orientation = (screen as { orientation?: Partial<LockableScreenOrientation> }).orientation
  return orientation &&
    typeof orientation.lock === 'function' &&
    typeof orientation.unlock === 'function'
    ? (orientation as LockableScreenOrientation)
    : undefined
}

/** 当前已请求（含在途）的锁值；null = 无锁。 */
let activeOrientationLock: LockableOrientation | null = null
/** 卸载后仍在途的 lock 迟到兑现时要立刻反向 unlock（防幽灵锁）。 */
let orientationDisposed = false

async function applyOrientationLock(): Promise<void> {
  const orientation = lockableScreenOrientation()
  const want = props.orientationLock
  if (!orientation || want === 'none' || !isFullscreen.value) return
  if (activeOrientationLock === want) return
  activeOrientationLock = want
  try {
    await orientation.lock(want)
    // A7 迟到锁竞态（二期 Wave 2）：在途期间切到别的方向（lock(P) 未兑现时
    // 又 lock(L)）后，P 的迟到兑现不再 unlock——activeOrientationLock 已指向
    // 有效的新锁，此处 unlock 会误拆它。只保留卸载兜底：卸载后仍在途的迟到
    // 兑现立即反向 unlock（防幽灵锁，「解锁泄漏」红线不变）。退全屏路径由
    // releaseOrientationLock 的同步 unlock 覆盖，平台按提交序处理，迟到兑现
    // 不会重新上锁。
    if (orientationDisposed) {
      activeOrientationLock = null
      try {
        orientation.unlock()
      } catch {
        // 幂等清理。
      }
    }
  } catch {
    // 被拒（iOS Safari / 非全屏 / 不支持）——静默降级，仅记偏好。
    if (activeOrientationLock === want) activeOrientationLock = null
  }
}

function releaseOrientationLock(): void {
  const orientation = lockableScreenOrientation()
  const held = activeOrientationLock
  activeOrientationLock = null
  if (held === null || !orientation) return
  try {
    orientation.unlock()
  } catch {
    // 无锁可解（浏览器已随退出全屏复位）——幂等清理。
  }
}

// 面板里切三态：none → 解锁；portrait/landscape → 全屏中即时换锁。
watch(
  () => props.orientationLock,
  (value) => {
    if (value !== 'none' && isFullscreen.value) void applyOrientationLock()
    else releaseOrientationLock()
  },
)

/* ------------------------------------------------------------------ */
/* Preload adjacent pages (next 2 / prev 1) for zero-wait turns        */
/* ------------------------------------------------------------------ */

let rootObserver: ResizeObserver | null = null

function devicePixelRatio(): number {
  return typeof window !== 'undefined' && window.devicePixelRatio > 0
    ? window.devicePixelRatio
    : 1
}

/** CSS width of ONE page in the current mode (dual splits the viewport). */
const perPageCssWidth = computed(() =>
  props.mode === 'dual' ? rootWidth.value / 2 : rootWidth.value,
)

const preloaded = new Set<string>()

function preloadPage(page: number) {
  if (page < 0 || (props.totalPages > 0 && page >= props.totalPages) || perPageCssWidth.value <= 0) return
  const url =
    props.enhancedUrls?.get(page) ??
    pageImageUrl(props.gid, page, perPageCssWidth.value * devicePixelRatio())
  if (preloaded.has(url)) return
  preloaded.add(url)
  const img = new Image()
  img.decoding = 'async'
  img.src = url
}

// The map's identity never changes (entries are added in place), so react
// to its size to re-run preloads when an enhanced version arrives.
watch(
  [() => props.currentPage, perPageCssWidth, () => props.enhancedUrls?.size],
  () => {
    const page = props.currentPage
    // Wave-1 1c: preloadCount (0 = off); prev page always primed for back-nav.
    const ahead = preferencesStore.prefs?.reader.preloadCount ?? 2
    for (let i = 1; i <= ahead; i++) preloadPage(page + i)
    preloadPage(page - 1)
  },
  { immediate: true },
)

onMounted(() => {
  // T1b/T1c/A5：文档/窗口级监听与根元素无关，先于 rootRef 早退守卫挂上。
  document.addEventListener('visibilitychange', onReaderVisibilityChange)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  window.addEventListener('keydown', onReaderKeyDown)
  // 进入时若已在全屏（浏览器级恢复/用户手动），先同步一次状态。
  isFullscreen.value = document.fullscreenElement != null
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
  // T1c：进入阅读器流程（打开阅读器的用户手势调用链）内请求真全屏。
  if (preferencesStore.prefs?.reader.fullscreen) void enterFullscreen()
  // T1b：阅读器激活即点亮屏幕常亮。
  void acquireWakeLock()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onReaderVisibilityChange)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  window.removeEventListener('keydown', onReaderKeyDown)
  // A7 红线：先置卸载旗标（在途 lock 迟到兑现时立刻反解锁），再主动解锁
  // ——方向锁绝不允许越过阅读器会话泄漏到后续页面。
  orientationDisposed = true
  releaseOrientationLock()
  // 退出阅读器路由 → 还原全屏（只还原自己进入的）。
  exitReaderFullscreen()
  void releaseWakeLock()
  rootObserver?.disconnect()
})

/* ------------------------------------------------------------------ */
/* Zoom resets per page (each page starts fit, like the Android pager) */
/* ------------------------------------------------------------------ */

watch(
  () => props.currentPage,
  () => {
    if (props.zoom !== 1) emit('update:zoom', 1)
  },
)

defineExpose({ toggleChrome, handleBack, isFullscreen, openJumpDialog })
</script>

<style scoped>
.image-reader {
  position: relative;
  width: 100%;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
  background: var(--grey-975); /* #080808 — reader backdrop */
}

/* GalleryHeader sits just under the toolbar scrim (which now grows by the
   status-bar / cutout inset). */
.image-reader__status-bar {
  position: absolute;
  top: calc(var(--toolbar-height) + var(--safe-area-top));
  left: 0;
  right: 0;
  z-index: 20;
  pointer-events: none;
}

/*
 * A6: 页面内容容器——提亮段（101–200）CSS filter 的载体。恒为 absolute
 * inset 0：filter 会让元素成为 absolute 后代的包含块，容器自身先定位好，
 * 几何（相对 .image-reader 铺满）在 filter 有/无两种状态下完全一致；
 * chrome（状态栏/工具栏/滑杆）留在容器外，不被提亮。
 */
.image-reader__pages {
  position: absolute;
  inset: 0;
  transition: filter var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

/* Bottom SeekBarPanel slides away with the chrome. Padded so the slider
   clears the home indicator; the slide-out transform still hides it fully. */
.image-reader__seekbar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 30;
  padding-bottom: var(--safe-area-bottom);
  transition:
    transform var(--duration-scene-translate) var(--ease-decelerate-quint),
    visibility 0s linear 0s;
}

.image-reader__seekbar--hidden {
  transform: translateY(100%);
  visibility: hidden;
  transition:
    transform var(--duration-scene-translate) var(--ease-decelerate-quint),
    visibility 0s linear var(--duration-scene-translate);
}

/* auto_transfer chip — floats above the seek bar, end-aligned (45dp icon
   + margins in activity_gallery.xml ≈ this pill). */
.image-reader__autoplay {
  position: absolute;
  right: var(--keyline-margin);
  /* Sits above the seekbar, which is itself raised by the home-indicator inset. */
  bottom: calc(var(--seekbar-panel-height) + var(--safe-area-bottom) + 12px);
  z-index: 40;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px 7px 9px;
  border: none;
  border-radius: 17px;
  background: color-mix(in srgb, var(--grey-800) 88%, transparent);
  color: var(--color-white);
  font-size: var(--text-super-small); /* 12sp */
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition:
    background 150ms var(--ease-decelerate-quart),
    transform 150ms var(--ease-decelerate-quart);
}

.image-reader__autoplay:hover {
  background: color-mix(in srgb, var(--grey-750) 90%, transparent);
}

.image-reader__autoplay:active {
  transform: scale(0.95);
}

.image-reader__autoplay:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.image-reader__autoplay-label {
  line-height: 1;
}

/* Brightness mask — always on top (the ColorView is the last child of the
   activity's FrameLayout) but never intercepts input. */
.image-reader__mask {
  position: absolute;
  inset: 0;
  z-index: 60;
  background: var(--color-black);
  pointer-events: none;
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

@media (prefers-reduced-motion: reduce) {
  .image-reader__seekbar,
  .image-reader__seekbar--hidden {
    transition-duration: 1ms;
  }
}

/* Android 系统返回手势的边缘指示条：跟随向内拖动进度变宽变实，松手
   未达阈值即随 state 重置消失。纯视觉层，不拦截任何触点。 */
.image-reader__edge-back {
  position: fixed;
  top: 0;
  bottom: 0;
  z-index: 70;
  pointer-events: none;
}

.image-reader__edge-back--left {
  left: 0;
  background: linear-gradient(to right, rgba(0, 150, 136, 0.55), transparent);
}

.image-reader__edge-back--right {
  right: 0;
  background: linear-gradient(to left, rgba(0, 150, 136, 0.55), transparent);
}
</style>
