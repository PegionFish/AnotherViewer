<template>
  <!-- P-F2 ③: 下载行（原 DownloadView 内联的进度行渲染原样搬入）。行接收
       item（深响应对象）+ speeds（gid→速率共享 record），进度字段变化只
       重渲染本行（父级虚拟窗口不重绘）。AppListRow 的 open/read/menu/select
       与操作钮 start/pause/cancel/delete 原载荷上抛。 -->
  <AppListRow
    :id="item.id"
    :gid="item.gid"
    :title="displayTitle"
    :subtitle="displaySubtitle"
    :thumb="item.thumb"
    :thumb-width="240"
    :aria-label="`${displayTitle} — ${stateLabelOf(item)}`"
    :selectable="selectMode"
    :selected="selected"
    :class="['download-item', `download-item--${stateKeyOf(item)}`]"
    @open="$emit('open', item.gid)"
    @read="$emit('read', item.gid)"
    @menu="$emit('menu', item.id)"
    @select="$emit('select', item.id)"
  >
    <template #meta>
      <CategoryChip v-if="chip" :category="chip" />
      <!-- W1b 偏好接线（SearchView 同一门控）：上传者（showUploader，
           打码开启一律不渲染——敏感字段）+ 画廊页数（showGalleryPages，
           服务器下发 >0 才显示）。 -->
      <span v-if="showUploader(item)" class="download-item__uploader">
        {{ item.uploader }}
      </span>
      <span v-if="showGalleryPages(item)" class="download-item__gallery-pages">
        {{ item.pages }}P
      </span>
      <span v-if="item.total > 0" class="download-item__pages">
        {{ item.done }}/{{ item.total }} pages
      </span>
      <!-- W5/W7: 阅读进度角标（下载行），与 GalleryCard 的显示语义一致。 -->
      <span
        v-if="showReadProgressBadge(item)"
        class="download-item__read-progress"
        data-testid="read-progress-badge"
      >
        {{ readProgressLabelOf(item) }}
      </span>
    </template>

    <!-- percent (left) + speed/ETA (right) — both text_super_small 12sp -->
    <div class="download-item__stats">
      <span class="download-item__percent">{{ percentTextOf(item) }}</span>
      <span
        v-if="statsTextOf(item, speeds[item.gid] ?? 0)"
        class="download-item__speed"
      >
        {{ statsTextOf(item, speeds[item.gid] ?? 0) }}
      </span>
    </div>

    <!-- Horizontal ProgressBar replica (determinate; slides when total is
         still unknown, mirroring Android's indeterminate fallback) -->
    <div
      class="download-item__track"
      role="progressbar"
      :aria-label="`Download progress for ${displayTitle}`"
      :aria-valuemin="0"
      :aria-valuemax="100"
      :aria-valuenow="isIndeterminate(item) ? undefined : percentOf(item)"
    >
      <div
        class="download-item__fill"
        :class="{
          'download-item__fill--indeterminate': isIndeterminate(item),
          'download-item__fill--sheen': isDownloading(item) && !isIndeterminate(item),
        }"
        :style="isIndeterminate(item) ? undefined : { width: `${percentOf(item)}%` }"
      />
    </div>

    <div class="download-item__footer">
      <!-- State text (Android: textColorThemeAccent, above the actions) -->
      <span class="download-item__state">
        <span class="download-item__state-dot" aria-hidden="true" />
        {{ stateLabelOf(item) }}
      </span>

      <!-- Action cluster: 40dp icons with 8dp padding, as in item_download.xml -->
      <div class="download-item__actions">
        <button
          v-if="canStart(item)"
          type="button"
          class="download-item__action"
          title="Start"
          aria-label="Start download"
          @click.stop="$emit('start', item.id)"
        >
          <AppIcon name="play-dark" size="24px" />
        </button>
        <button
          v-if="canPause(item)"
          type="button"
          class="download-item__action"
          title="Pause"
          aria-label="Pause download"
          @click.stop="$emit('pause', item.id)"
        >
          <AppIcon name="pause-dark" size="24px" />
        </button>
        <button
          v-if="canCancel(item)"
          type="button"
          class="download-item__action"
          title="Stop"
          aria-label="Stop download"
          @click.stop="$emit('cancel', item.id)"
        >
          <AppIcon name="close-dark" size="24px" />
        </button>
        <button
          type="button"
          class="download-item__action download-item__action--danger"
          title="Delete"
          aria-label="Delete download"
          @click.stop="$emit('delete', item.id)"
        >
          <AppIcon name="delete-dark" size="24px" />
        </button>
      </div>
    </div>

    <!-- Failure reason (DownloadInfo.error), secondary text under the state -->
    <p
      v-if="isFailed(item) && errorTextOf(item)"
      class="download-item__error"
      :title="errorTextOf(item)"
    >
      {{ errorTextOf(item) }}
    </p>
  </AppListRow>
</template>

<script setup lang="ts">
/**
 * DownloadProgressRow — 下载列表的单行渲染（P-F2 ③，自 DownloadView.vue
 * 原样抽出，虚拟化 `<li>` 与窗口定位仍归宿主视图）。抽组件的目的：WS 进度
 * 消息改字段（done/state/total/label）与速率（speeds 的 gid 键）时，只有
 * 收到字段的那一行重渲染——父级模板不再深度读取行字段，虚拟窗口其余行
 * （及其 `<li>`）零重绘。
 *
 * 展示语义（脱敏标题/偏好门控/状态色/操作钮集合）与抽取前逐像素等价：
 * class、DOM 结构、样式声明均原样搬入（scoped 样式随组件走，选择器不变）。
 */
import { computed } from 'vue'
import type { DownloadItem } from '@/api/download'
import { usePreferencesStore } from '@/stores/preferences'
import { maskedTitle, privacyMaskEnabled } from '@/utils/privacyMask'
import { isJpnSubtitleVisible } from '@/utils/jpnSubtitle'
import { CATEGORY_BY_BIT, type GalleryCategory } from '@/types/components'
import AppIcon from '@/components/atoms/AppIcon.vue'
import CategoryChip from '@/components/atoms/CategoryChip.vue'
import AppListRow from '@/components/gallery/AppListRow.vue'

/** Android `DownloadInfo.STATE_*` (anotherviewer-web mirrors the constants). */
const STATE_NONE = 0
const STATE_WAIT = 1
const STATE_DOWNLOAD = 2
const STATE_FINISH = 3
const STATE_FAILED = 4

const props = withDefaults(
  defineProps<{
    /** 行数据（usePagedList items 的深响应元素；进度字段被 WS 帧批量改写）。 */
    item: DownloadItem
    /**
     * gid → 实时速率（pages/s）的共享 record。传整表而非单值 prop：速率
     * 键的写放只在表内发生（引用不变），父级不跟踪键 → 只有读取自己 gid
     * 键的本行重渲染（传 `speed` 单值会让父级每帧重绘整个窗口）。
     */
    speeds: Record<number, number>
    /** 多选模式（Android choice mode）。 */
    selectMode?: boolean
    /** 多选选中态。 */
    selected?: boolean
  }>(),
  { selectMode: false, selected: false },
)

defineEmits<{
  /** 缩略图 → 详情页（载荷 = gid，AppListRow 同名事件透传）。 */
  (e: 'open', gid: number): void
  /** 主体 → 直接阅读（载荷 = gid）。 */
  (e: 'read', gid: number): void
  /** 右键 / 长按 → 宿主进入多选（载荷 = id）。 */
  (e: 'menu', id: number): void
  /** 多选下点主体 → 切换选中（载荷 = id）。 */
  (e: 'select', id: number): void
  /** 操作钮：开始（载荷 = id）。 */
  (e: 'start', id: number): void
  /** 操作钮：暂停（载荷 = id）。 */
  (e: 'pause', id: number): void
  /** 操作钮：停止（载荷 = id）。 */
  (e: 'cancel', id: number): void
  /** 操作钮：删除（载荷 = id）。 */
  (e: 'delete', id: number): void
}>()

const item = computed(() => props.item)

const preferencesStore = usePreferencesStore()

/* --------------------------------------------------- row presentation --- */
/* 与原 DownloadView 逐行同源：脱敏/偏好门控/状态派生全在这里完成，宿主
   视图不再重复这些依赖（prefs 变化经 store 响应性直达本组件）。 */

/** 行展示标题——脱敏在本组件完成（与原 DownloadItem 同一表达式）。 */
const displayTitle = computed(() =>
  maskedTitle(props.item.title || props.item.titleJpn || 'Untitled', props.item.gid),
)

/**
 * General 偏好快捷视图（W1b：showJpnTitle / showUploader / showGalleryPages，
 * SearchView 同源读取）。prefs 未加载时为 undefined——上传者/页数按防御默认
 * 隐藏；日文副题的未加载防闪失兜底在 isJpnSubtitleVisible 内部处理。
 */
const generalPrefs = computed(() => preferencesStore.prefs?.general)

/**
 * 行副题（日文标题）：打码开启或 showJpnTitle 关闭时隐藏（SearchView
 * displaySubtitle 同一门控；prefs 未加载按显示渲染防闪失——T2 定案）。
 */
const displaySubtitle = computed(() => {
  if (privacyMaskEnabled.value) return null
  if (!props.item.titleJpn) return null
  if (!isJpnSubtitleVisible(generalPrefs.value)) return null
  return props.item.titleJpn
})

/**
 * 上传者（B-2 信息开关）：严格 `=== true` 才显示；隐私打码开启时属敏感
 * 内容一律隐藏（SearchView/GalleryCard 同语义）。
 */
function showUploader(target: DownloadItem): boolean {
  return (
    generalPrefs.value?.showUploader === true &&
    !privacyMaskEnabled.value &&
    Boolean(target.uploader)
  )
}

/**
 * 画廊页数（`{{ pages }}P`）：偏好开启且服务器下发了 >0 的页数才显示
 * （`pages` 可能为 0 / 旧服务器缺省 undefined → 隐藏）。
 */
function showGalleryPages(target: DownloadItem): boolean {
  return generalPrefs.value?.showGalleryPages === true && (target.pages ?? 0) > 0
}

/* W5/W7 (plan-2026-09-02): 阅读进度角标——showReadProgress 开且进度 > 0 才
   显示；格式对齐 Android GalleryAdapterNew：N/MP（有总页数）或 NP（页数
   未知）。字段缺失（旧服务器 undefined）时隐藏，与 GalleryCard 语义一致。 */
function readProgressLabelOf(target: DownloadItem): string {
  const progress = target.readProgress
  if (typeof progress !== 'number' || !Number.isFinite(progress) || progress <= 0) return ''
  const current = progress + 1
  return target.total > 0 ? `${current}/${target.total}P` : `${current}P`
}

function showReadProgressBadge(target: DownloadItem): boolean {
  return preferencesStore.prefs?.general?.showReadProgress === true && readProgressLabelOf(target) !== ''
}

/** 分类 chip（原由宿主 virtualRows 合成，随行渲染搬入）。 */
const chip = computed<GalleryCategory | undefined>(() => CATEGORY_BY_BIT[props.item.category])

function isDownloading(target: DownloadItem): boolean {
  return target.state === STATE_DOWNLOAD
}

function isFailed(target: DownloadItem): boolean {
  return target.state === STATE_FAILED
}

/** Failure reason from the backend; empty when the item has none. */
function errorTextOf(target: DownloadItem): string {
  return target.error?.trim() || ''
}

/** Downloading with an unknown page count → sliding indeterminate bar. */
function isIndeterminate(target: DownloadItem): boolean {
  return isDownloading(target) && target.total <= 0
}

function percentOf(target: DownloadItem): number {
  return target.total > 0 ? Math.min(100, Math.round((target.done / target.total) * 100)) : 0
}

function percentTextOf(target: DownloadItem): string {
  return target.total > 0 ? `${percentOf(target)}%` : '—'
}

/** CSS modifier key for the current state (S4 state colors on the row). */
function stateKeyOf(target: DownloadItem): string {
  switch (target.state) {
    case STATE_NONE:
      return 'idle'
    case STATE_WAIT:
      return 'wait'
    case STATE_DOWNLOAD:
      return 'download'
    case STATE_FINISH:
      return 'finish'
    default:
      return 'failed'
  }
}

/** Android `download_state_*` strings (values-en/strings.xml:377-383). */
function stateLabelOf(target: DownloadItem): string {
  switch (target.state) {
    case STATE_NONE:
      return 'Idle'
    case STATE_WAIT:
      return 'Waiting'
    case STATE_DOWNLOAD:
      return 'Downloading'
    case STATE_FINISH:
      return 'Done'
    default:
      return 'Failed'
  }
}

/** Android shows the start icon when idle or failed (retry). */
function canStart(target: DownloadItem): boolean {
  return target.state === STATE_NONE || target.state === STATE_FAILED
}

function canPause(target: DownloadItem): boolean {
  return isDownloading(target)
}

function canCancel(target: DownloadItem): boolean {
  return target.state === STATE_WAIT || isDownloading(target)
}

/** Seconds remaining at the current rate (0 = not computable). */
function etaSecondsOf(target: DownloadItem, speed: number): number {
  if (speed <= 0 || target.total <= 0) return 0
  return Math.max(0, target.total - target.done) / speed
}

function formatEta(seconds: number): string {
  const s = Math.round(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (h > 0) return `${h}h ${m}m`
  const sec = s % 60
  if (m > 0) return `${m}m ${sec}s`
  return `${sec}s`
}

/** Right-hand stats text: rate + ETA while downloading. */
function statsTextOf(target: DownloadItem, speed: number): string {
  if (!isDownloading(target)) return ''
  if (speed <= 0) return 'Fetching…'
  const rate = speed >= 10 ? speed.toFixed(0) : speed.toFixed(1)
  const eta = etaSecondsOf(target, speed) > 0 ? ` · ETA ${formatEta(etaSecondsOf(target, speed))}` : ''
  return `${rate} pages/s${eta}`
}
</script>

<!-- 行内部样式原样搬入（P-F2 ③）：scoped 选择器与声明不变——元素改由本
     组件渲染后命中本组件的 scope id，计算样式与抽取前逐像素等价。 -->
<style scoped>
/* ------------------------------------------- download row internals ---- */
/* W3-C1：行骨架（卡片面/勾选圈/缩略图/标题）在共享 AppListRow 内；这里只
   保留下载特有的 slotted 内容样式（原 DownloadItem.vue 逐条搬入，声明不变
   ——视觉零漂移）。`download-item--*` 状态修饰类由模板随行下发到 AppListRow
   根节点，用于给进度条/状态文案着色（S4 状态色）。 */
.download-item__pages {
  margin-left: auto;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* W5/W7: 阅读进度角标 — 12sp secondary，跟在下载进度文案之后。 */
.download-item__read-progress {
  flex-shrink: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* W1b: 上传者（showUploader）— 12sp secondary，超长省略；打码开启不渲染。 */
.download-item__uploader {
  min-width: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* W1b: 画廊页数（showGalleryPages）— 12sp secondary，`N P` 计数稳定对齐。 */
.download-item__gallery-pages {
  flex-shrink: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ------------------------------------------------------------- stats --- */
.download-item__stats {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--spacing);
  font-size: var(--text-super-small); /* 12sp, percent + speed row */
}

.download-item__percent {
  color: var(--text-color-primary);
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.download-item__speed {
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ----------------------------------------------------- progress bar --- */
.download-item__track {
  height: 4px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--grey-300); /* S4 spec: grey-300 track (light) */
}

/* Theme-aware tracks matching Android progress_dark / progress_black. */
[data-theme='dark'] .download-item__track {
  background: var(--grey-600);
}

[data-theme='black'] .download-item__track {
  background: var(--grey-700);
}

.download-item__fill {
  position: relative;
  height: 100%;
  border-radius: 2px;
  overflow: hidden;
  background: var(--grey-500);
  transition:
    width 300ms var(--ease-decelerate-quart),
    background-color 200ms linear;
}

/* State colors: downloading = accent, idle/wait = grey, done = green,
   failed = red (S4 style spec). */
.download-item--download .download-item__fill {
  background: var(--color-accent);
}

.download-item--finish .download-item__fill {
  background: var(--color-cat-game-cg);
}

.download-item--failed .download-item__fill {
  background: var(--color-red-500);
}

/* Live sheen sweeping across the fill while downloading. */
.download-item__fill--sheen::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent 0%, var(--translucent-bg) 50%, transparent 100%);
  transform: translateX(-100%);
  animation: dl-sheen 1300ms linear infinite;
}

@keyframes dl-sheen {
  to {
    transform: translateX(100%);
  }
}

/* Unknown page count: Material-style sliding segment. */
.download-item__fill--indeterminate {
  width: 40%;
  background: var(--color-accent);
  animation: dl-slide 1400ms var(--ease-decelerate-quart) infinite;
}

@keyframes dl-slide {
  0% {
    margin-left: -40%;
  }
  100% {
    margin-left: 100%;
  }
}

/* ------------------------------------------------------------ footer --- */
.download-item__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing);
  margin-top: auto;
}

.download-item__state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-super-small); /* 12sp */
  font-weight: 500;
  color: var(--grey-500);
}

.download-item--download .download-item__state {
  color: var(--color-accent);
}

.download-item--finish .download-item__state {
  color: var(--color-cat-game-cg);
}

.download-item--failed .download-item__state {
  color: var(--color-red-500);
}

.download-item__state-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.download-item--download .download-item__state-dot {
  animation: dl-pulse 1000ms ease-in-out infinite;
}

@keyframes dl-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

/* ----------------------------------------------------- failure reason --- */
.download-item__error {
  margin: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* ----------------------------------------------------------- actions --- */
.download-item__actions {
  display: flex;
  align-items: center;
}

/* 40dp touch targets with 24dp glyphs (item_download.xml ImageViews). */
.download-item__action {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--drawable-color-primary);
  cursor: pointer;
  transition:
    background-color 140ms var(--ease-decelerate-quart),
    color 140ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.download-item__action:hover {
  background: var(--color-surface-activated);
}

.download-item__action:active {
  transform: scale(0.88);
}

.download-item__action:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

.download-item__action--danger:hover {
  color: var(--color-red-500);
}

@media (prefers-reduced-motion: reduce) {
  .download-item__fill--sheen::after,
  .download-item__fill--indeterminate,
  .download-item--download .download-item__state-dot {
    animation: none;
  }
}
</style>
