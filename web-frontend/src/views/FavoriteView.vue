<template>
  <div class="favorite-view">
    <div class="favorite-view__heading">
      <h1 class="favorite-view__title">Favorites</h1>
      <span v-if="state === 'content'" class="favorite-view__count">
        {{ countLabel }}
      </span>
    </div>

    <!-- Server-side search + filter slots (A5d): 防抖 q 搜索，与筛选槽位互斥
         （useFilterSlots——选槽位清搜索、输入搜索取消槽位）。 -->
    <div class="search-bar">
      <AppIcon name="magnify-dark" size="18px" />
      <input
        v-model="searchQuery"
        class="search-bar__input"
        type="search"
        :placeholder="filterSlot ? `筛选：${filterSlot.name}` : '搜索标题…'"
        aria-label="搜索收藏"
        @compositionstart="searchComposing = true"
        @compositionend="onSearchCompositionEnd"
      />
      <button
        v-if="searchQuery"
        type="button"
        class="search-bar__clear"
        aria-label="清除搜索"
        @click="clearSearch"
      >
        <AppIcon name="close-dark" size="16px" />
      </button>
    </div>

    <FilterSlotBar :slots="slots" :active-id="activeSlotId" @select="onSlotBarSelect" />

    <!-- Favorite folder filter — Android FavoritesScene's folder spinner,
         reimagined as a scrollable chip strip. Names come from
         `prefs.general.favoriteSlotNames` (B-4, `|`-separated), empty slots
         on the SiteConfig.DEFAULT_FAV_CAT_NAMES defaults ("Favorites 0" …
         "Favorites 9"). -->
    <nav class="slot-bar" aria-label="Favorite folders">
      <!-- "All" covers every folder the server knows, including the Android
           local-favorites slot (-2) which no numbered tab reaches. -->
      <button
        type="button"
        class="slot-bar__chip"
        :class="{ 'slot-bar__chip--active': activeSlot === -1 }"
        :aria-current="activeSlot === -1 ? 'true' : undefined"
        @click="selectSlot(-1, $event)"
      >
        {{ 'All' }}
      </button>
      <button
        v-for="(name, slot) in slotNames"
        :key="slot"
        type="button"
        class="slot-bar__chip"
        :class="{ 'slot-bar__chip--active': activeSlot === slot }"
        :aria-current="activeSlot === slot ? 'true' : undefined"
        @click="selectSlot(slot, $event)"
      >
        {{ name }}
      </button>
    </nav>

    <!-- 分页条（A4 定案：与下载/历史页同构，2026-09-06）：页码窗口 + 前后页 +
         跳页 + PC 键盘翻页。服务端 /favorite/list 现固定 20 条/页（W2-B2 只在
         FavoriteService 层支持 pageSize，控制器尚未暴露 pageSize 查询参数——
         对照 HistoryController），条数切换档位（50/100/200，对齐下载页）待
         后端补齐后解锁。total ≤ pageSize 时隐藏（Android PaginationIndicator
         语义）。 -->
    <nav
      v-if="paginationVisible"
      class="pagination-bar"
      data-testid="favorite-pagination"
      aria-label="收藏分页"
    >
      <span class="pagination-bar__info">
        第 {{ currentPage }} / {{ totalPages }} 页 · {{ total }} 条
      </span>
      <span class="pagination-bar__pages" role="group" aria-label="页码">
        <button
          type="button"
          class="pagination-bar__page"
          :disabled="currentPage <= 1"
          aria-label="上一页"
          @click="jumpToPage(currentPage - 1)"
        >
          ‹
        </button>
        <template v-for="(item, i) in pageWindow" :key="`${item}-${i}`">
          <button
            v-if="item !== '…'"
            type="button"
            class="pagination-bar__page"
            :class="{ 'pagination-bar__page--active': item === currentPage }"
            :aria-current="item === currentPage ? 'page' : undefined"
            :aria-label="`第 ${item} 页`"
            @click="jumpToPage(item)"
          >
            {{ item }}
          </button>
          <span v-else class="pagination-bar__ellipsis" aria-hidden="true">…</span>
        </template>
        <button
          type="button"
          class="pagination-bar__page"
          :disabled="currentPage >= totalPages"
          aria-label="下一页"
          @click="jumpToPage(currentPage + 1)"
        >
          ›
        </button>
      </span>
      <span class="pagination-bar__jump">
        <input
          v-model.number="jumpInput"
          class="pagination-bar__input"
          type="number"
          min="1"
          :max="totalPages"
          :aria-label="`跳页（1 至 ${totalPages}）`"
          @keyup.enter="jumpToPage()"
          placeholder="页"
        />
        <button type="button" class="pagination-bar__btn" @click="jumpToPage()">
          跳页
        </button>
      </span>
    </nav>

    <ContentLayout
      ref="contentRef"
      class="favorite-view__content"
      :state="state"
      v-model:refreshing="refreshing"
      empty-text="No favorites"
      :error-text="errorText"
      @refresh="onRefresh"
      @retry="onRetry"
    >
      <!-- A4 定案（W3-F4）：与下载/历史页同构的全宽单列密信息行——共享
           AppListRow（缩略图→详情 / 主体→统一阅读器 点击分区 + 角标挂点）。
           服务端分页（W2-B2 DB 分页）：usePagedList 把 1 起页码直传给
           /favorite/list（收藏信封 page 1 起，历史是 0 起），整页替换渲染。

           #badge 挂收藏夹角标（F-UX5：♥ + 条目真实 favoriteSlot——tab 0 混合
           slot -1/0，旧服务器缺字段回落当前页签号；slot -1 只出♥，与 Android
           徽章无数字一致）。#meta 放 CategoryChip + W6 阅读进度角标。

           KeepAlive（App.vue 按 fullPath 缓存实例）：页码与页内滚动位置随
           组件实例存续，从阅读器/详情返回即还原——页码还原语义 = 页码 +
           页内滚动。 -->
      <div class="favorite-list">
        <AppListRow
          v-for="row in rows"
          :key="row.item.gid"
          :id="row.item.gid"
          :gid="row.item.gid"
          :title="displayTitle(row.item)"
          :subtitle="displaySubtitle(row.item)"
          :thumb="row.item.thumb"
          @open="openDetail"
          @read="openReader"
        >
          <!-- Favorite folder badge — heart + folder number, accent
               background; absolutely positioned corner badge anchored to the
               row (AppListRow badge mount). -->
          <template #badge>
            <span class="slot-badge" :title="`In ${slotBadgeName(row.slot)}`">
              <AppIcon name="heart" size="12px" />
              <template v-if="row.slot >= 0">{{ row.slot }}</template>
            </span>
          </template>

          <!-- 元信息行：CategoryChip + W6 阅读进度角标（N+1P，语义同
               GalleryCard：showReadProgress 开且进度 > 0 才显示）。 -->
          <template #meta>
            <CategoryChip v-if="row.chip" :category="row.chip" />
            <span
              v-if="showReadProgressBadge(row.item)"
              class="favorite-item__read-progress"
              data-testid="read-progress-badge"
            >
              {{ readProgressLabelOf(row.item) }}
            </span>
          </template>
        </AppListRow>
      </div>
    </ContentLayout>

    <!-- FabLayout replica: refresh + back-to-top mini FABs
         (scene_favorites.xml v_refresh / v_go_to cluster) -->
    <FabLayout
      v-model:expanded="fabExpanded"
      primary-icon="reorder"
      :actions="fabActions"
      @click-secondary="onFabAction"
    />

    <Teleport to="body">
      <!-- Toast (Android Toast equivalent, F4) -->
      <div v-if="toastMessage" class="toast" role="status">{{ toastMessage }}</div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
/**
 * FavoriteView — web replica of Android `FavoritesScene`:
 * ContentLayout (pull-to-refresh + empty tip) filled with the shared
 * `AppListRow` single-column rows (A4 定案，W3-F4——与下载/历史页完全同构：
 * 缩略图→详情 / 主体→直接阅读 点击分区；`#badge` 挂收藏夹角标，`#meta` 放
 * CategoryChip + 阅读进度角标)，加收藏夹过滤条（slots 0–9，名字来自
 * `prefs.general.favoriteSlotNames`，B-4）与场景 FabLayout（刷新 / 回顶部）。
 *
 * 服务端分页（A4 / W2-B2 DB 分页）：`usePagedList` 状态机管理页码/跳页/PC
 * 键盘翻页；`fetchPage` 把 1 起页码直传 /favorite/list（收藏信封 page 1 起，
 * 历史是 0 起；响应信封 `{favorites, totalPages, currentPage}` + 新增
 * `page/pageSize/total`，total 驱动 totalPages；旧服务器缺 total 时以
 * legacy totalPages×页大小 复原，页数口径与旧 envelope 一致）。
 * 偏离 A4 档位定案：/favorite/list 控制器暂不收 pageSize（服务固定 20 条/页），
 * 条数档位（50/100/200）待后端暴露后再解锁——见分页条注释。
 * Search（q，防抖）与 filter slots（A5d，q=pattern&regex=true）收窄服务端
 * 结果，两者互斥且都随变更回第 1 页。
 *
 * F-UX5 — tab semantics align with the Android FavoritesScene: tab 0 is the
 * DEFAULT FOLDER (server filters `favoriteSlot in (-1, 0)`), tabs 1-9 are
 * the custom folders (`favoriteSlot == N`). The chip strip still sends
 * 0-9 exactly as before. Rows carry a heart badge with the item's REAL
 * favoriteSlot (FavoriteItem.favoriteSlot，tab 0 混合 -1/0)。
 *
 * KeepAlive（App.vue 按 fullPath 缓存实例）：页码与页内滚动位置随组件实例
 * 存续，从阅读器/详情返回即还原。
 *
 * 隐私红线：标题一律经 `maskedTitle`（打码开启 → `#<gid>`）；日文副题在
 * 打码开启时一并隐藏（同 GalleryCard 的 `!privacyMaskEnabled` 守卫）。
 * R4-6: 无标题画廊以 `#<gid>` 展示。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { favoriteApi } from '@/api/favorite'
import type { FavoriteItem, FavoriteListResponse } from '@/api/favorite'
import { useFilterSlots } from '@/composables/useFilterSlots'
import { usePagedList } from '@/composables/usePagedList'
import { maskedTitle, privacyMaskEnabled } from '@/utils/privacyMask'
import FilterSlotBar from '@/components/FilterSlotBar.vue'
import {
  CATEGORY_BIT_VALUES,
  CATEGORY_BY_BIT,
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  type FabAction,
  type GalleryCategory,
} from '@/types/components'
import ContentLayout from '@/components/layout/ContentLayout.vue'
import FabLayout from '@/components/atoms/FabLayout.vue'
import AppListRow from '@/components/gallery/AppListRow.vue'
import AppIcon from '@/components/atoms/AppIcon.vue'
import CategoryChip from '@/components/atoms/CategoryChip.vue'
import { parseFavoriteSlotNames } from '@/components/gallery/GalleryCard.vue'
import { usePreferencesStore } from '@/stores/preferences'

/** View states matching ContentLayout's internal ViewTransition. */
type ViewState = 'loading' | 'content' | 'empty' | 'error'

const preferencesStore = usePreferencesStore()

/**
 * B-4: the 10 folder names from `general.favoriteSlotNames` (`|`-separated),
 * empty slots on the `SiteConfig.DEFAULT_FAV_CAT_NAMES` defaults. Read
 * defensively — the key is added by a parallel settings-schema stream, so
 * unloaded prefs or a missing key simply yield the defaults the strip has
 * always shown.
 */
const slotNames = computed(() =>
  parseFavoriteSlotNames(
    (preferencesStore.prefs?.general as { favoriteSlotNames?: string } | undefined)
      ?.favoriteSlotNames,
  ),
)

/** Unknown category fallback — Android `SiteUtils.UNKNOWN` bit. */
const CATEGORY_UNKNOWN_BIT = 0x400

/* ---------------------------------------------- category string → bit --- */

const NAME_TO_BIT = new Map<string, number>()
for (const key of CATEGORY_ORDER) {
  NAME_TO_BIT.set(CATEGORY_LABELS[key].toLowerCase(), CATEGORY_BIT_VALUES[key])
  NAME_TO_BIT.set(key, CATEGORY_BIT_VALUES[key])
}

/**
 * Normalizes a backend category value to its `SiteConfig` bit value.
 * Accepts integer bits (2), stringified bits ("2"), labels ("Artist CG") and
 * keys ("artist_cg").
 */
function categoryBit(raw: number | string): number {
  if (typeof raw === 'number') return raw
  const trimmed = raw.trim()
  if (trimmed !== '' && !Number.isNaN(Number(trimmed))) return Number(trimmed)
  return NAME_TO_BIT.get(trimmed.toLowerCase()) ?? CATEGORY_UNKNOWN_BIT
}

/* --------------------------------------------------------------- data --- */

const router = useRouter()

/**
 * 服务端分页口径（W2-B2）：/favorite/list 的 service 层 pageSize 默认且当前
 * 唯一实效值 20——控制器尚未暴露 pageSize 查询参数（对照 HistoryController），
 * 客户端不发会被忽略的条数参数；档位切换（50/100/200，对齐下载/历史页）待
 * 后端补齐后解锁。
 */
const FAVORITE_PAGE_SIZE = 20
const FAVORITE_PAGE_SIZES = [FAVORITE_PAGE_SIZE] as const

const state = ref<ViewState>('loading')
const refreshing = ref(false)
const contentRef = ref<InstanceType<typeof ContentLayout> | null>(null)
/** F4 REGEX_INVALID: the error tip switches to a dedicated regex message. */
const errorText = ref('Failed to load favorites')

/** 标题计数：服务端过滤后全集条数（分页不再累计已加载数）。 */
const countLabel = computed(() => `${total.value} galleries`)

/* ---------------------------------------- search + filter slots (A5d) ----- */

/** 搜索词：防抖后作为 q 传给 /favorite/list（服务端过滤）。 */
const searchQuery = ref('')
const debouncedQuery = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined

/**
 * 筛选槽位（A5d）：命名正则预设；与搜索框互斥（useFilterSlots 保证）——
 * 选槽位清空 searchQuery、输入搜索取消槽位。重命名为 filterSlot 以避免与
 * 收藏夹标签 activeSlot（0-9）冲突。
 */
const { slots, activeSlotId, activeSlot: filterSlot, selectSlot: selectFilterSlot } =
  useFilterSlots(searchQuery)

function onSlotBarSelect(id: string | null): void {
  selectFilterSlot(id)
  // 槽位点击总是重新加载（清空搜索词不一定触发防抖 watch——搜索词本来就空时）。
  // 非静默 load → onLoadStart 切 loading 态。
  void load()
}

/** 当前筛选条件：槽位激活 → (q=pattern, regex=true)；否则 → 搜索词（LIKE）。 */
function currentFilter(): { q: string | null; regex: boolean } {
  const slot = filterSlot.value
  if (slot) return { q: slot.pattern, regex: true }
  return { q: debouncedQuery.value || null, regex: false }
}

watch(searchQuery, scheduleSearchCommit)

/* IME 组合输入保护（plan-2026-09-05 C1）：拼音组合期间的 input 事件携带
   中间态字母——组合置位时防抖回调直接丢弃，compositionend 后由最终选词的
   input 事件重新走防抖提交。 */
const searchComposing = ref(false)

function onSearchCompositionEnd(): void {
  searchComposing.value = false
  scheduleSearchCommit()
}

/** 防抖提交搜索词（watch 与 compositionend 共用同一时钟）。 */
function scheduleSearchCommit(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (searchComposing.value) return
    // 搜索词变化 → 回第 1 页重新加载（负载在服务端）。
    const next = searchQuery.value
    if (debouncedQuery.value !== next) {
      debouncedQuery.value = next
      // 槽位激活时该变更来自 selectFilterSlot 清空搜索词——加载已由
      // onSlotBarSelect 触发（避免与槽位过滤重复请求）。
      if (filterSlot.value) return
      void load()
    }
  }, 400)
}

function clearSearch(): void {
  searchQuery.value = ''
  debouncedQuery.value = ''
  void load()
}

/* ---------------------------------------------------- pagination bar ---- */

/**
 * F4: extracts the business error code from the API error envelope
 * (`{error:{code,message,traceId,status}}` carried by axios as
 * `error.response.data`); null for any other failure shape.
 */
function errorCodeOf(error: unknown): string | null {
  const code = (error as { response?: { data?: { error?: { code?: unknown } } } } | undefined)
    ?.response?.data?.error?.code
  return typeof code === 'string' ? code : null
}

/** 当前收藏夹页签（0-9；-1 = All，服务端映射 slot < 0 全量）。 */
const activeSlot = ref(0)

/**
 * 分页状态机（W3-C1 usePagedList）：页码 / 跳页 / PC 页码窗口 /
 * PageUp/Down / stale 竞态守卫。fetchPage 适配 /favorite/list 信封——
 * 收藏 page 1 起【直传】（无历史的 -1 换算）；pageSize 服务端固定 20
 * （见 FAVORITE_PAGE_SIZE 注释）。视图四态机与错误文案留在本视图，
 * 经 onLoadStart/onSuccess/onError 钩子接线。
 */
const {
  items: favorites,
  total,
  currentPage,
  totalPages,
  paginationVisible,
  pageWindow,
  jumpInput,
  load: loadPage,
  jumpToPage,
} = usePagedList<FavoriteItem>({
  fetchPage: async (page) => {
    const filter = currentFilter()
    const response = await favoriteApi.listFavorites(
      activeSlot.value,
      page,
      filter.q || null,
      filter.regex || undefined,
    )
    // W2-B2 信封新增 page/pageSize/total（favorite.ts 类型尚未声明——运行时
    // 存在）：total 驱动 totalPages。旧服务器缺 total 时以 legacy
    // totalPages×页大小 复原（ceil 恒等于旧 totalPages，口径不漂移）。
    const envelope = response as FavoriteListResponse & { total?: number }
    return {
      items: response.favorites,
      total:
        typeof envelope.total === 'number'
          ? envelope.total
          : response.totalPages * FAVORITE_PAGE_SIZE,
    }
  },
  pageSizes: FAVORITE_PAGE_SIZES,
  initialPageSize: FAVORITE_PAGE_SIZE,
  fallbackPageSize: FAVORITE_PAGE_SIZE,
  onLoadStart: () => {
    state.value = 'loading'
  },
  onSuccess: (result) => {
    state.value = result.items.length === 0 ? 'empty' : 'content'
    contentRef.value?.scrollToTop()
  },
  onError: (error) => {
    console.error('Failed to load favorites', error)
    // F4: invalid regex in q → 400 REGEX_INVALID; name the cause instead of
    // the generic "failed to load" tip (dedicated toast + error-state copy).
    if (errorCodeOf(error) === 'REGEX_INVALID') {
      errorText.value = '正则无效，请检查搜索/筛选的正则表达式'
      showToast('正则无效，请检查筛选表达式')
    } else {
      errorText.value = 'Failed to load favorites'
    }
    if (favorites.value.length === 0) {
      state.value = 'error'
    } else if (errorCodeOf(error) !== 'REGEX_INVALID') {
      showToast('Failed to refresh favorites')
    }
  },
  keyboardPaging: true,
})

/**
 * 视图层加载入口：搜索/槽位/刷新等所有入口都回第 1 页。`silent: true`
 * 不切 loading 态——下拉刷新等宿主自管忙态的入口。
 */
function load(opts?: { silent?: boolean }): Promise<void> {
  return loadPage(1, opts)
}

async function onRefresh(): Promise<void> {
  await load({ silent: true })
  refreshing.value = false
}

function onRetry(): void {
  void load()
}

/* --------------------------------------------------- row presentation --- */

/** 行展示标题——脱敏在本视图完成（隐私红线：标题必经 maskedTitle）。 */
function displayTitle(item: FavoriteItem): string {
  return maskedTitle(item.title || item.titleJpn || `#${item.gid}`, item.gid)
}

/** 日文副题：打码开启时一并隐藏（同 GalleryCard 的标题日文行守卫）。 */
function displaySubtitle(item: FavoriteItem): string | null {
  return !privacyMaskEnabled.value && item.titleJpn ? item.titleJpn : null
}

/** Numeric category bit → `GalleryCategory` key (undefined when unknown). */
function categoryKeyOf(item: FavoriteItem): GalleryCategory | undefined {
  return CATEGORY_BY_BIT[categoryBit(item.category)]
}

/**
 * F-UX5: the row's REAL slot rides along (FavoriteItem.favoriteSlot) so the
 * ♥ badge shows the true folder — tab 0 mixes slots -1 and 0. Legacy
 * servers without the field fall back to the active tab number.
 */
function rowSlot(item: FavoriteItem): number {
  return item.favoriteSlot ?? activeSlot.value
}

/** Folder display name for the badge title of an item with the given slot
 *  — slot -1 (default folder) is named by tab 0, mirroring the scene. */
function slotBadgeName(slot: number): string {
  return slotNames.value[slot >= 0 ? slot : 0] ?? ''
}

/**
 * Per-row view models: the raw favorite item plus the pre-resolved category
 * chip key (v-if narrows the property, not a function call — same shape as
 * HistoryView's rows.chip) and the ♥ badge slot number.
 */
const rows = computed(() =>
  favorites.value.map((item) => ({
    item,
    chip: categoryKeyOf(item),
    slot: rowSlot(item),
  })),
)

/* W6 (plan-2026-09-02): 阅读进度角标——showReadProgress 开且进度 > 0 才
   显示；收藏行带的是同 gid 历史行的 0 起页索引 → N+1P 格式（对齐
   GalleryCard/历史行语义）。字段缺失（旧服务器 undefined）时隐藏。 */
function readProgressLabelOf(item: FavoriteItem): string {
  const progress = item.readProgress
  if (typeof progress !== 'number' || !Number.isFinite(progress) || progress <= 0) return ''
  return `${progress + 1}P`
}

function showReadProgressBadge(item: FavoriteItem): boolean {
  const prefs = preferencesStore.prefs?.general as { showReadProgress?: boolean } | undefined
  return prefs?.showReadProgress === true && readProgressLabelOf(item) !== ''
}

/* ------------------------------------------------- folder chip strip --- */

function selectSlot(slot: number, event: MouseEvent): void {
  if (slot === activeSlot.value) return
  activeSlot.value = slot
  const el = event.currentTarget
  if (el instanceof HTMLElement) {
    el.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' })
  }
  void load()
}

/* --------------------------------------------------- click partitions --- */

/** 缩略图点击 → 详情页；P-A：本地 token 透传（收藏行若无历史/下载背书，
 *  服务端凭 token 上游直取）。 */
function openDetail(gid: number): void {
  const item = favorites.value.find((entry) => entry.gid === gid)
  void router.push({
    path: `/gallery/${gid}`,
    query: item?.token ? { token: item.token } : {},
  })
}

/** 行主体点击 → 直接进统一阅读器（A4 点击分区，快速续读）。 */
function openReader(gid: number): void {
  const item = favorites.value.find((entry) => entry.gid === gid)
  void router.push({
    path: `/reader/${gid}`,
    query: item?.token ? { token: item.token } : {},
  })
}

/* --------------------------------------------------------------- FAB ---- */

const fabExpanded = ref(false)

const fabActions: FabAction[] = [
  { id: 'refresh', icon: 'refresh-dark', label: 'Refresh favorites' },
  { id: 'scroll-top', icon: 'go-to-dark', label: 'Back to top' },
]

function onFabAction(action: FabAction): void {
  fabExpanded.value = false
  if (action.id === 'refresh') {
    void load()
  } else if (action.id === 'scroll-top') {
    contentRef.value?.scrollToTop()
  }
}

/* -------------------------------------------------------------- toast --- */

const toastMessage = ref('')
let toastTimer: ReturnType<typeof setTimeout> | undefined

function showToast(message: string): void {
  toastMessage.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toastMessage.value = ''
  }, 2400)
}

onUnmounted(() => {
  clearTimeout(toastTimer)
})

onMounted(() => {
  // Folder names (B-4) come from preferences. The chip strip renders before
  // the content state does — kick the load here so custom names appear as
  // early as possible.
  if (!preferencesStore.prefs && !preferencesStore.loading) {
    void preferencesStore.load()
  }
  void load()
})
</script>

<style scoped>
.favorite-view {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  /* Standalone PWA: push the header row + slot bar + list below the status
     bar / cutout. border-box keeps the column at 100dvh — the flex:1
     ContentLayout shrinks instead of overflowing. The list bottom already
     clears the home indicator via --gallery-padding-bottom-fab. */
  padding-top: var(--safe-area-top);
  background: var(--color-bg);
}

.favorite-view__content {
  flex: 1;
  min-height: 0;
}

/* ------------------------------------------------------------ heading --- */
.favorite-view__heading {
  display: flex;
  align-items: baseline;
  gap: var(--spacing);
  flex-shrink: 0;
  padding: 14px max(var(--gallery-list-margin-h), 4px) 0;
}

.favorite-view__title {
  font-size: var(--text-super-large); /* 24sp */
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text-color-primary);
}

.favorite-view__count {
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
}

/* 汉堡可见视口（<720px，或横屏矮视口）：顶部标题行避让浮动汉堡；
   列表内容全宽，从汉堡下穿过（FAB 语义）。 */
@media (max-width: 719px), (min-width: 720px) and (max-height: 479.98px) {
  .favorite-view__heading {
    padding-left: var(--hamburger-clearance);
  }
}

/* ------------------------------------------------- server-side search ---- */
.search-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  margin: var(--spacing) var(--keyline-margin) 8px;
  padding: 0 10px;
  border: 1px solid var(--color-divider);
  border-radius: 999px;
  background: var(--color-surface);
  color: var(--text-color-secondary);
}

.search-bar__input {
  flex: 1 1 auto;
  min-width: 0;
  padding: 8px 0;
  border: none;
  background: transparent;
  color: var(--text-color-primary);
  font-family: inherit;
  font-size: var(--text-small);
  outline: none;
}

.search-bar__input::placeholder {
  color: var(--text-color-secondary);
}

/* 键盘焦点可见（C7）：pill 容器内的输入框用内嵌 outline。 */
.search-bar__input:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: -2px;
}

/* 触控目标加大（B5/C 附加项）：24px 图标钮 → 32px 命中区 + padding。 */
.search-bar__clear {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 4px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-color-secondary);
  cursor: pointer;
}

.search-bar__clear:hover {
  background: var(--color-surface-activated);
}

/* ----------------------------------------------------------- slot bar --- */
.slot-bar {
  display: flex;
  gap: var(--spacing);
  flex-shrink: 0;
  overflow-x: auto;
  padding: var(--spacing) max(var(--gallery-list-margin-h), 4px);
  border-bottom: 1px solid var(--color-divider);
  scrollbar-width: none;
}

.slot-bar::-webkit-scrollbar {
  display: none;
}

.slot-bar__chip {
  flex: 0 0 auto;
  padding: 5px 14px;
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius); /* 2dp — CheckTextView, not a pill */
  background: transparent;
  color: var(--text-color-secondary);
  font-family: inherit;
  font-size: var(--text-super-small); /* 12sp */
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 160ms var(--ease-decelerate-quart),
    border-color 160ms var(--ease-decelerate-quart),
    color 160ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.slot-bar__chip:hover {
  border-color: var(--color-primary);
  color: var(--text-color-primary);
}

.slot-bar__chip:active {
  transform: scale(0.95);
}

.slot-bar__chip--active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-white);
}

.slot-bar__chip--active:hover {
  color: var(--color-white);
}

.slot-bar__chip:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

/* ----------------------------------------------------- pagination bar ---- */
/* 与 DownloadView / HistoryView 分页条同构复刻（A4）：页码窗口 / 跳页
   （条数切换待 /favorite/list 暴露 pageSize 后补——见模板注释）。 */
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing);
  flex-shrink: 0;
  padding: 6px max(var(--gallery-list-margin-h), 4px);
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-divider);
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
}

.pagination-bar__info {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* PC 页码窗口：直点页码 + 省略号折叠 + 前后页。 */
.pagination-bar__pages {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: 0 1 auto;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.pagination-bar__pages::-webkit-scrollbar {
  display: none;
}

.pagination-bar__page {
  min-width: 26px;
  padding: 2px 5px;
  border: 1px solid transparent;
  border-radius: var(--card-radius);
  background: transparent;
  color: var(--color-primary);
  font-family: inherit;
  font-size: var(--text-super-small);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: background-color 140ms var(--ease-decelerate-quart);
}

.pagination-bar__page:hover:not(:disabled) {
  background: var(--color-surface-activated);
}

.pagination-bar__page:disabled {
  color: var(--text-color-disabled, #9e9e9e);
  cursor: default;
}

.pagination-bar__page--active {
  background: var(--color-primary);
  color: var(--color-primary-inverse, #fff);
  border-color: var(--color-primary);
}

.pagination-bar__page--active:hover {
  background: var(--color-primary);
}

.pagination-bar__ellipsis {
  min-width: 18px;
  text-align: center;
  color: var(--text-color-secondary);
  user-select: none;
}

.pagination-bar__jump {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: 0 0 auto;
  white-space: nowrap;
}

.pagination-bar__input {
  padding: 2px 6px;
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius);
  background: var(--color-surface);
  color: var(--text-color-primary);
  font-family: inherit;
  font-size: var(--text-super-small);
}

.pagination-bar__input {
  width: 52px;
  -moz-appearance: textfield;
  appearance: textfield;
}

.pagination-bar__input::-webkit-outer-spin-button,
.pagination-bar__input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.pagination-bar__input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.pagination-bar__btn {
  padding: 2px 8px;
  border: none;
  border-radius: var(--card-radius);
  background: transparent;
  color: var(--color-primary);
  font-family: inherit;
  font-size: var(--text-super-small);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 140ms var(--ease-decelerate-quart);
}

.pagination-bar__btn:hover {
  background: var(--color-surface-activated);
}

/* -------------------------------------------------------------- list ---- */
/* Single-column dense rows (A4): the row skeleton (card surface / thumb /
   title) lives in the shared AppListRow; only the favorites-specific folder
   badge is styled here. */
.favorite-list {
  padding: var(--gallery-list-margin-v) var(--gallery-list-margin-h)
    var(--gallery-padding-bottom-fab);
}

/* ------------------------------------------------------------- toast ---- */
.toast {
  position: fixed;
  left: 50%;
  /* The FAB cluster is offset by --safe-area-bottom (FabLayout) — carry the
     same inset so the toast keeps its distance above the FABs / home
     indicator on cutout devices. */
  bottom: calc(96px + var(--safe-area-bottom));
  transform: translateX(-50%);
  z-index: 300;
  padding: 10px 20px;
  background: var(--grey-850);
  color: var(--grey-100);
  border-radius: var(--card-radius);
  font-size: var(--text-small);
  box-shadow: 0 3px 10px var(--shadow-color);
  opacity: 1;
  animation: toast-in 220ms var(--ease-decelerate-quint);
}

@keyframes toast-in {
  from {
    opacity: 0;
    transform: translateX(-50%) translateY(10px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .toast {
    animation: none;
  }
}

/* Favorite slot indicator — heart + folder number, accent background.
   Absolute corner badge anchored to the AppListRow row (its badge slot). */
.slot-badge {
  position: absolute;
  right: 10px;
  bottom: 10px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: var(--card-radius);
  background: var(--color-accent);
  color: var(--color-white);
  font-size: var(--text-super-small); /* 12sp */
  font-weight: 600;
  line-height: 1.4;
  box-shadow: 0 1px 3px var(--shadow-color);
  pointer-events: none;
}

/* W6: 阅读进度角标 — 12sp secondary，跟在分类 chip 之后。 */
.favorite-item__read-progress {
  flex-shrink: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
