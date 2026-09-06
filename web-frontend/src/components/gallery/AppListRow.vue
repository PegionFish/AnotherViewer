<template>
  <article
    class="app-list-row"
    :class="{ 'app-list-row--selectable': selectable, 'app-list-row--selected': selected }"
    :aria-selected="selectable ? selected : undefined"
    @contextmenu="onContextMenu"
    @touchstart.passive="onTouchStart"
    @touchmove.passive="onTouchMove"
    @touchend="onTouchEnd"
    @touchcancel="onTouchEnd"
    @click="onBodyClick"
  >
    <!-- Optional corner badge mount (History's last-viewed stamp, Favorites'
         slot badge…): absolutely positioned by the consumer, anchored to this
         row (the article is the containing block). -->
    <slot name="badge" />

    <!-- Multi-select indicator (Android checked circle, choice mode only) -->
    <span
      v-if="selectable"
      class="app-list-row__check"
      :class="{ 'app-list-row__check--on': selected }"
      aria-hidden="true"
    >
      <AppIcon v-if="selected" name="check-dark" size="16px" color="var(--color-white)" />
    </span>

    <!-- FixedThumb replica: 80×120dp, CENTER_CROP (item_download.xml /
         item_gallery_list.xml). 点击分区 1：缩略图 → 详情（stop 防触发主体
         的阅读；键盘 Enter 同址）。 -->
    <div
      class="app-list-row__thumb"
      role="link"
      tabindex="0"
      :aria-label="`${title} — 详情`"
      @click.stop="emit('open', gid)"
      @keydown.enter.stop="emit('open', gid)"
    >
      <img
        v-if="hasThumb"
        :src="thumbSrc ?? undefined"
        :alt="title"
        loading="lazy"
        @error="onThumbError"
      />
      <div v-else class="app-list-row__thumb-placeholder" aria-hidden="true">
        <AppIcon name="download-primary" size="28px" />
      </div>
    </div>

    <div class="app-list-row__body">
      <!-- CardTitle: 16sp, maxLines 2, end-ellipsize。title 由消费方脱敏
           （maskedTitle）后传入——本组件不碰隐私打码逻辑。 -->
      <h3 class="app-list-row__title" :title="title">{{ title }}</h3>
      <!-- 可选副题（日文标题等），单行省略，对齐 gallery-card__title-jpn。 -->
      <p v-if="subtitle" class="app-list-row__subtitle">{{ subtitle }}</p>

      <!-- 元信息行（CategoryChip / 页数 / 阅读进度角标 等，消费方组合）。 -->
      <div v-if="$slots.meta" class="app-list-row__meta">
        <slot name="meta" />
      </div>

      <!-- 行主体扩展 slot：下载特有字段（percent/速率/进度条/状态/操作钮）
           走这里；历史/收藏等轻行可以完全不提供。 -->
      <slot />
    </div>
  </article>
</template>

<script setup lang="ts">
/**
 * AppListRow — 全宽单列密信息行（A4 定案，W3-C1 抽取）：下载/首页/搜索/
 * 历史/收藏所有列表视图共用的行骨架 = 缩略图 + 标题/副题 + 元信息行 +
 * 可选角标 + 行内点击分区。
 *
 * 点击分区（Android 端逻辑，下载页现行为）：
 * - 缩略图（role=link，含键盘 Enter）→ `open`（详情页）；
 * - 主体（非缩略图/按钮）→ 多选模式下切换选中（`select`），否则 `read`
 *   （直接进统一阅读器）；
 * - 右键 / 长按 ≥500ms（位移 <10px）→ `menu`（宿主进入 choice mode）。
 *
 * 视图差异全部由 slot 表达：`meta`（元信息行）、默认 slot（下载的
 * percent/速率/进度条/状态/操作钮）、`badge`（绝对定位角标，历史/收藏用）。
 * 缩略图处理与 DownloadItem/GalleryCard 同语义：外部 http(s) 走 WebUI
 * 图片代理（CSP img-src 'self'），加载失败换图标占位（alt 不泄漏）。
 */
import { computed, ref } from 'vue'
import AppIcon from '@/components/atoms/AppIcon.vue'
import { resolveApiUrl } from '@/stores/server'

const props = withDefaults(
  defineProps<{
    /** 行业务 id（`menu`/`select` 载荷；下载行 = DownloadItem.id）。 */
    id: number
    /** 画廊 gid（`open`/`read` 载荷）。 */
    gid: number
    /** 展示标题——消费方负责脱敏（maskedTitle(title‖titleJpn‖'Untitled', gid)）。 */
    title: string
    /** 可选副题（日文标题等）。 */
    subtitle?: string | null
    /** 缩略图原始地址；空/加载失败渲染图标占位。 */
    thumb?: string | null
    /** 多选模式（Android custom choice mode）：行可点选并显示勾选态。 */
    selectable?: boolean
    /** 多选选中态（驱动勾选圈与选中描边）。 */
    selected?: boolean
  }>(),
  { subtitle: null, thumb: null, selectable: false, selected: false },
)

const emit = defineEmits<{
  /** 缩略图点击/Enter → 详情页。 */
  (e: 'open', gid: number): void
  /** 主体点击（非多选）→ 直接阅读。 */
  (e: 'read', gid: number): void
  /** 右键 / 长按 → 宿主进入多选并勾选本行（Android onItemLongClick）。 */
  (e: 'menu', id: number): void
  /** 多选模式下点主体 → 切换本行选中。 */
  (e: 'select', id: number): void
}>()

/** Thumbnail failure/absence flag — swaps the row to the icon placeholder so
    a broken `<img>` never leaks its `alt` (the title) into the grey box
    (E2E-9 / E2E-3). */
const thumbFailed = ref(false)

/** A usable thumb source; null/empty renders the placeholder outright. */
const hasThumb = computed(() => Boolean(props.thumb) && !thumbFailed.value)

/**
 * Rewritten thumbnail URL (plan-2026-08-06 A7): external `http(s)` thumbnails
 * go through the WebUI image proxy (`/api/v1/image/proxy`), because the site
 * CSP only allows `img-src 'self'`; local paths pass through unchanged.
 */
const thumbSrc = computed<string | null>(() => {
  const thumb = props.thumb
  if (!thumb) return null
  // 隐私打码不改 src——真实请求照发，像素由全局遮蔽样式隐藏。
  return /^https?:\/\//i.test(thumb) ? resolveApiUrl(`/image/proxy?url=${encodeURIComponent(thumb)}`) : thumb
})

function onThumbError(): void {
  thumbFailed.value = true
}

/* ---- multi-select (Android custom choice mode) ---- */

let pressTimer: ReturnType<typeof setTimeout> | null = null
let pressStartX = 0
let pressStartY = 0

/** Long-press (≥500ms, <10px movement) → host menu (Android onItemLongClick).
    无条件触发——即使尚未处于多选模式（长按即进入）。 */
function onTouchStart(event: TouchEvent): void {
  const touch = event.touches[0]
  pressStartX = touch.clientX
  pressStartY = touch.clientY
  clearPressTimer()
  pressTimer = setTimeout(() => {
    pressTimer = null
    emit('menu', props.id)
  }, 500)
}

function onTouchMove(event: TouchEvent): void {
  if (!pressTimer) return
  const touch = event.touches[0]
  if (Math.abs(touch.clientX - pressStartX) > 10 || Math.abs(touch.clientY - pressStartY) > 10) {
    clearPressTimer()
  }
}

function onTouchEnd(): void {
  clearPressTimer()
}

function clearPressTimer(): void {
  if (pressTimer) {
    clearTimeout(pressTimer)
    pressTimer = null
  }
}

/** Right-click → host menu；row 上永不弹出原生菜单（无条件，长按/右键即进入多选）。 */
function onContextMenu(event: MouseEvent): void {
  event.preventDefault()
  emit('menu', props.id)
}

/** Card body click in select mode toggles the row (Android toggleItemChecked);
    action buttons keep their own handlers (closest('button') guard);
    非多选主体点击 → 直接阅读（快速续读）。 */
function onBodyClick(event: MouseEvent): void {
  if (!props.selectable) {
    emit('read', props.gid)
    return
  }
  if ((event.target as HTMLElement).closest('button')) return
  emit('select', props.id)
}
</script>

<style scoped>
/* CardView.Reactive replica: 2dp radius / elevation / margin, theme surface. */
.app-list-row {
  position: relative;
  display: flex;
  align-items: stretch;
  background: var(--color-surface);
  border-radius: var(--card-radius);
  box-shadow: 0 var(--card-elevation) var(--card-max-elevation) var(--shadow-color);
  margin: 2px;
  overflow: hidden;
  transition:
    box-shadow 160ms var(--ease-decelerate-quart),
    transform 160ms var(--ease-decelerate-quart);
}

.app-list-row:hover {
  box-shadow: 0 var(--card-elevation) calc(var(--card-max-elevation) * 3) var(--shadow-color);
  transform: translateY(-1px);
}

/* --------------------------------------------- multi-select (choice mode) --- */

.app-list-row--selectable {
  cursor: pointer;
}

.app-list-row--selectable:hover {
  transform: none;
}

.app-list-row--selected {
  box-shadow:
    inset 0 0 0 2px var(--color-primary),
    0 var(--card-elevation) var(--card-max-elevation) var(--shadow-color);
}

.app-list-row__check {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid var(--color-divider);
  background: var(--color-bg);
}

.app-list-row__check--on {
  border-color: var(--color-primary);
  background: var(--color-primary);
}

/* ------------------------------------------------------------- thumb --- */
.app-list-row__thumb {
  flex: 0 0 var(--thumb-list-width); /* 80px */
  width: var(--thumb-list-width);
  height: var(--thumb-list-height); /* 120px */
  overflow: hidden;
  background: var(--color-divider);
}

.app-list-row__thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover; /* FixedThumb CENTER_CROP */
  transition: transform 300ms var(--ease-decelerate-quart);
}

.app-list-row:hover .app-list-row__thumb img {
  transform: scale(1.04);
}

.app-list-row__thumb-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--drawable-color-secondary);
}

/* -------------------------------------------------------------- body --- */
.app-list-row__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: var(--spacing); /* 8px, item_download.xml margins */
}

.app-list-row__title {
  margin: 0;
  font-size: var(--text-little-small); /* 16sp CardTitle */
  font-weight: 500;
  line-height: 1.35;
  color: var(--text-color-primary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 可选副题（日文标题等）——对齐 gallery-card__title-jpn。 */
.app-list-row__subtitle {
  margin: 0;
  font-size: clamp(11px, var(--text-super-small), 14px); /* 12sp ideal */
  line-height: 1.35;
  color: var(--text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 元信息行：chip / 页数 / 角标 的水平容器（消费方子元素自管样式）。 */
.app-list-row__meta {
  display: flex;
  align-items: center;
  gap: var(--spacing);
  min-width: 0;
}

@media (prefers-reduced-motion: reduce) {
  .app-list-row:hover .app-list-row__thumb img {
    animation: none;
    transform: none;
  }
}
</style>
