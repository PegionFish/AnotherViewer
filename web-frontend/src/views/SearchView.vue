<!--
  SearchView.vue — dedicated search scene (S5), web equivalent of Android
  `GalleryListScene` in search mode.

  Composition (all frozen-contract components):
    NavigationDrawer — global nav (modal < 720px, persistent ≥ 720px);
    SearchBar        — floating bar with the normal → search → search-list
                       state machine; suggestions = search history +
                       quick-search presets (long-press deletes history);
    FilterPanel      — the SINGLE filter surface (W3 R4-10 convergence):
                       keyword mode + categories + sort + page bounds +
                       min rating + scope + advanced options; the legacy
                       search panel is retired and removed from the repo
                       (W4 cleanup; only the Android widget remains);
    ContentLayout    — results with pull-to-refresh (W3-F2 A4: infinite
                       paging retired in favor of the pagination bar);
    AppListRow       — shared single-column dense info row (W3-C1): thumb →
                       detail / body → reader click partitions; the row title
                       is masked here via `maskedTitle` (隐私红线不动);
    pagination bar   — DownloadView-aligned pager (page window + prev/next +
                       jump). Upstream search is fixed at 25 rows/page (total
                       = upstream pages × 25) so there is no page-size switch;
    FabLayout        — primary FAB opens the FilterPanel, secondary FABs
                       manage quick searches.

  Query composition mirrors Android `formatListUrlBuilder`:
    - categories use POSITIVE semantics here and are converted to the
      SiteConfig EXCLUSION bitmask (CATEGORY_BIT_VALUES) for the API;
    - uploader / tag keyword modes prefix the keyword (`uploader:` / `tag:`),
      the canonical AnotherViewer URL syntax;
    - Wave-1 1a (task A5): sort / pageMin / pageMax / minRating / the four
      search-scope flags travel as the `filters` argument of
      `galleryApi.search` (extended backend params) and round-trip through
      QuickSearchDto via `searchFilters.ts`;
    - W3 R4-10: the higher AdvanceSearchTable bits are backendized too
      (searchTorrentsOnly / searchLowPowerTags / searchDownvotedTags /
      searchExpunged / disableLanguageFilter / disableUploaderFilter /
      disableTagFilter), so presets and searches carry the full 11-bit mask.

  Wave-1 1a additives (task A5):
    FilterPanel   — anchored PC popover (SearchBar filter button / `f` key);
    chip row      — active filters under the SearchBar (per-chip × + Clear);
    quick search  — save POSTs the QuickSearchDto payload (sort included,
                    W3 R4-11), with a device-local fallback when the server
                    is unreachable; delete calls DELETE /quick-search/{id}
                    (W3 R4-12) with an offline local-removal fallback;
    shortcuts     — `/` focuses search, `f` toggles the filter panel
                    (suppressed while typing in editable elements).

  Persistence (client-side):
    anotherviewer-search-history   — recent keyword searches (capped by
                                     prefs.general.recentSearchMax, default 10,
                                     0 disables recording);
    anotherviewer-quick-searches   — user presets (seeded from GET /gallery/quick-search).
                                     The legacy `anotherviewer-search-view-mode`
                                     key is no longer read or written (W3-F2 A4:
                                     single-column list only — stale values on
                                     old devices are simply ignored).

  KeepAlive (W3-F2 A4): page restore semantics = page number + in-page scroll.
  The page number lives in the component instance (usePagedList state) so it
  survives deactivation; the in-page scroll offset is restored by
  ContentLayout's scrollMemory (keyed by fullPath).
-->
<template>
  <div class="search-scene">
    <NavigationDrawer
      v-model:open="drawerOpen"
      :items="DEFAULT_NAV_ITEMS"
      :active-item-id="'homepage'"
      :username="authStore.username ?? undefined"
      :theme="themeStore.currentTheme"
      @select="onNavSelect"
      @toggle-theme="themeStore.toggleTheme()"
    />

    <div class="search-scene__main">
      <!-- Floating search bar + anchored FilterPanel popover (Wave-1 1a).
           The wrapper is the popover's positioning anchor. -->
      <div class="search-scene__filter-anchor">
        <SearchBar
          ref="searchBarRef"
          :state="searchBarState"
          :title="searchTitle"
          :query="query"
          hint="Search galleries"
          left-icon="reorder"
          right-icon="magnify-dark"
          :suggestions="suggestions"
          filter-visible
          :filter-panel-open="filterPanelOpen"
          :filter-active="activeFilterChips.length > 0"
          :filter-chips="activeFilterChips"
          @update:state="searchBarState = $event"
          @update:query="onQueryInput"
          @search="commitSearch"
          @click-menu="drawerOpen = true"
          @click-action="commitSearch(query)"
          @click-title="openSearch"
          @select-suggestion="onSelectSuggestion"
          @dismiss-suggestion="onDismissSuggestion"
          @back="closeSearch"
          @click-filter="filterPanelOpen = !filterPanelOpen"
          @remove-filter-chip="onRemoveFilterChip"
          @clear-filter-chips="onClearFilters"
        />

        <!-- PC filter popover — the SINGLE filter surface (W3 R4-10):
             keyword mode + categories + sort + pages + rating + scope +
             advanced options. The legacy search panel is retired. -->
        <FilterPanel
          v-model:open="filterPanelOpen"
          v-model:keyword-mode="normalSearchMode"
          :filters="activeFilters"
          @update:filters="applyFilters"
          @search="onFilterPanelSearch"
          @save-quick-search="openSaveDialog"
        />
      </div>

      <!-- 分页条（W3-F2 A4：完全参照下载页逻辑；上游搜索固定 25 条/页，
           total = 上游页数×25，因此没有条数切换——仅页码窗口 + 前后页 +
           跳页）。页码状态在组件实例里，KeepAlive 停用/还原即「页码+页内
           滚动」（页内滚动由 ContentLayout scrollMemory 还原）。 -->
      <nav
        v-if="paginationVisible"
        class="pagination-bar"
        data-testid="search-pagination"
        aria-label="搜索分页"
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

      <!-- Results: pull-to-refresh via ContentLayout. W3-F2 (A4): the infinite
           scroll footer is retired — pages swap wholesale from the bar above. -->
      <ContentLayout
        ref="contentRef"
        class="search-scene__content"
        :state="contentState"
        :refreshing="refreshing"
        empty-text="No galleries found — adjust the filters and try again"
        error-text="Search failed — check the connection and retry"
        @update:refreshing="refreshing = $event"
        @refresh="onRefresh"
        @retry="onRefresh"
      >
        <!-- 单列密信息行（A4）：AppListRow 点击分区 = 缩略图→详情、主体→阅读。
             标题在本视图经 maskedTitle 脱敏后下发（红线）。搜索无多选，`menu`
             （长按/右键）不接线。 -->
        <div class="results">
          <AppListRow
            v-for="{ gallery, chip } in rows"
            :key="gallery.gid"
            :id="gallery.gid"
            :gid="gallery.gid"
            :title="displayTitle(gallery)"
            :subtitle="displaySubtitle(gallery)"
            :thumb="gallery.thumb"
            @open="openGallery"
            @read="openReader"
          >
            <template #meta>
              <CategoryChip v-if="chip" :category="chip" />
              <!-- W5: 阅读进度角标顶替页数文案（GalleryCard 同语义）。 -->
              <span
                v-if="showReadProgressBadge(gallery)"
                class="search-item__read-progress"
                data-testid="read-progress-badge"
              >
                {{ readProgressLabel(gallery) }}
              </span>
              <span v-else-if="gallery.pages > 0" class="search-item__pages">
                {{ gallery.pages }}P
              </span>
              <RatingStars :rating="gallery.rating" />
            </template>

            <!-- Preference-gated info switches (B-2) + tags, mirroring the
                 GalleryCard list form；打码开启时 uploader/tags 一律隐藏。 -->
            <div
              v-if="
                showUploader(gallery) || showPostedTime(gallery) || rowTags(gallery).length > 0
              "
              class="search-item__extra"
            >
              <span v-if="showUploader(gallery)" class="search-item__uploader">
                {{ gallery.uploader }}
              </span>
              <span v-if="showPostedTime(gallery)" class="search-item__posted">
                {{ gallery.posted }}
              </span>
              <span v-for="tag in rowTags(gallery)" :key="tag" class="search-item__tag">
                {{ tag }}
              </span>
            </div>
          </AppListRow>
        </div>
      </ContentLayout>

      <!-- FAB cluster: primary opens filters, secondaries manage presets. -->
      <FabLayout
        v-model:expanded="fabExpanded"
        primary-icon="magnify-dark"
        :actions="fabActions"
        @click-primary="onFabPrimary"
        @click-secondary="onFabSecondary"
      />
    </div>

    <!-- Quick-search management dialog. -->
    <Transition name="dialog">
      <div
        v-if="dialog === 'manage'"
        class="dialog-scrim"
        @click.self="dialog = 'none'"
      >
        <div class="dialog" role="dialog" aria-modal="true" aria-label="Quick searches">
          <h2 class="dialog__title">Quick searches</h2>
          <ul v-if="quickSearches.length" class="qs-list">
            <li v-for="preset in quickSearches" :key="preset.id" class="qs-item">
              <button type="button" class="qs-item__main" @click="loadQuickSearch(preset)">
                <span class="qs-item__name">{{ preset.name }}</span>
                <span class="qs-item__summary">
                  {{ preset.keyword || '(no keyword)' }} · {{ modeLabel(preset.mode) }}
                </span>
              </button>
              <button
                type="button"
                class="qs-item__delete"
                :aria-label="`Delete ${preset.name}`"
                @click="deleteQuickSearch(preset.id)"
              >
                <AppIcon name="delete-dark" size="20px" />
              </button>
            </li>
          </ul>
          <p v-else class="dialog__empty">
            No presets yet — configure the filters and tap “Save quick search”.
          </p>
          <div class="dialog__actions">
            <button type="button" class="btn-text" @click="openSaveDialog">
              Save current…
            </button>
            <button type="button" class="btn-text" @click="dialog = 'none'">Close</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Save-preset dialog. -->
    <Transition name="dialog">
      <div
        v-if="dialog === 'save'"
        class="dialog-scrim"
        @click.self="dialog = 'none'"
      >
        <div class="dialog" role="dialog" aria-modal="true" aria-label="Save quick search">
          <h2 class="dialog__title">Save quick search</h2>
          <p class="dialog__summary">
            {{ query.trim() || '(no keyword)' }} · {{ modeLabel(MODE_TO_NUM[normalSearchMode]) }}
            · {{ selectedCategories.length }}/10 categories
            <template v-if="activeFilterChips.length">
              · {{ activeFilterChips.map((chip) => chip.label).join(' · ') }}
            </template>
          </p>
          <label class="field">
            <input
              v-model="presetName"
              type="text"
              placeholder=" "
              maxlength="40"
              @keydown.enter.prevent="saveQuickSearch"
            />
            <span class="field__label">Preset name</span>
          </label>
          <div class="dialog__actions">
            <button type="button" class="btn-text" @click="dialog = 'none'">Cancel</button>
            <button type="button" class="btn-primary" @click="saveQuickSearch">Save</button>
          </div>
        </div>
      </div>
    </Transition>

    <!-- Snackbar. -->
    <Transition name="snack">
      <div v-if="snack" class="snackbar" role="status">{{ snack }}</div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type {
  AdvanceSearchOptions,
  FabAction,
  GalleryCategory,
  GalleryInfo,
  NavItem,
  NormalSearchMode,
  SearchBarState,
  SearchSuggestion,
} from '@/types/components'
import { CATEGORY_BY_BIT, CATEGORY_ORDER } from '@/types/components'
import type { QuickSearch } from '@/types'
import { galleryApi } from '@/api/gallery'
import type { SearchFilters, SearchSortOrder } from '@/api/gallery'
import type { GeneralPreferences } from '@/api/preferences'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { usePreferencesStore } from '@/stores/preferences'
import { usePagedList } from '@/composables/usePagedList'
import { maskedTitle, privacyMaskEnabled } from '@/utils/privacyMask'
import NavigationDrawer, { DEFAULT_NAV_ITEMS } from '@/components/layout/NavigationDrawer.vue'
import SearchBar from '@/components/search/SearchBar.vue'
import FilterPanel from '@/components/search/FilterPanel.vue'
import ContentLayout from '@/components/layout/ContentLayout.vue'
import AppListRow from '@/components/gallery/AppListRow.vue'
import CategoryChip from '@/components/atoms/CategoryChip.vue'
import RatingStars from '@/components/atoms/RatingStars.vue'
import FabLayout from '@/components/atoms/FabLayout.vue'
import AppIcon from '@/components/atoms/AppIcon.vue'
import type { FilterChip } from '@/components/search/searchFilters'
import {
  ALL_ADVANCE_ITEMS,
  filterChips,
  filtersToQuickSearchPayload,
  includedToMask,
  maskToIncluded,
  removeFilterChip,
} from '@/components/search/searchFilters'

const router = useRouter()
const authStore = useAuthStore()
const themeStore = useThemeStore()
const preferencesStore = usePreferencesStore()

/* ------------------------------- constants ------------------------------ */

const PAGE_SIZE = 25
const HISTORY_KEY = 'anotherviewer-search-history'
const QUICK_SEARCH_KEY = 'anotherviewer-quick-searches'
/**
 * W3-F2 (A4): the legacy `anotherviewer-search-view-mode` key is retired —
 * the view is single-column only. Stale values on existing devices are left
 * untouched and simply ignored (no migration read).
 */
/**
 * Recent-search cap — `prefs.general.recentSearchMax` (Wave-1 1b key, added
 * by A3). The preferences schema may not carry it yet, so it is read with
 * optional chaining: absent/invalid → 10, 0 → feature off (MASTER §3.2 B-5).
 */
const RECENT_SEARCH_DEFAULT_MAX = 10

/** QuickSearch.mode numbering (Android `QuickSearch` / ListUrlBuilder modes). */
const MODE_TO_NUM: Readonly<Record<NormalSearchMode, number>> = {
  normal: 0,
  subscription: 1,
  uploader: 2,
  tag: 3,
}
const NUM_TO_MODE: Readonly<Record<number, NormalSearchMode>> = {
  0: 'normal',
  1: 'subscription',
  2: 'uploader',
  3: 'tag',
}
const MODE_LABELS: Readonly<Record<NormalSearchMode, string>> = {
  normal: 'Search',
  subscription: 'Subscription',
  uploader: 'Uploader',
  tag: 'Tag',
}

/** Drawer item id → route (items without a screen yet fall back to home). */
const NAV_ROUTES: Readonly<Record<string, string>> = {
  homepage: '/',
  favourite: '/favorites',
  history: '/history',
  downloads: '/downloads',
  settings: '/settings',
}

/* --------------------------------- state -------------------------------- */

const drawerOpen = ref(false)

// SearchBar state machine (Android SearchBar.STATE_*).
const searchBarRef = ref<InstanceType<typeof SearchBar> | null>(null)
const searchBarState = ref<SearchBarState>('normal')
const searchTitle = ref('Search')
const query = ref('')
/** Keyword of the last committed search (drives the query sent to the API). */
const activeQuery = ref('')

// Canonical filter state (W3 R4-10: the FilterPanel is the single filter
// surface; the legacy search panel is retired).
const selectedCategories = ref<GalleryCategory[]>([...CATEGORY_ORDER])
const normalSearchMode = ref<NormalSearchMode>('normal')
const advanceOptions = ref<AdvanceSearchOptions>({
  advanceSearch: 0,
  minRating: 0,
  pageFrom: 0,
  pageTo: 0,
})

/* --- Wave-1 1a (task A5): PC filter panel state -------------------------
   Canonical state stays in selectedCategories / advanceOptions / sortOrder;
   `activeFilters` is the derived SearchFilters object sent to the API and
   rendered as chips. W3 R4-10: the full 11-bit AdvanceSearchTable mask is
   backendized, so every bit round-trips through `activeFilters`. */
const sortOrder = ref<SearchSortOrder>(0)
const filterPanelOpen = ref(false)

const activeFilters = computed<SearchFilters>(() => {
  const advance = advanceOptions.value
  const filters: SearchFilters = {
    category: categoryParam(),
    sort: sortOrder.value === 0 ? undefined : sortOrder.value,
    pageMin: advance.pageFrom > 0 ? advance.pageFrom : undefined,
    pageMax: advance.pageTo > 0 ? advance.pageTo : undefined,
    minRating: advance.minRating > 0 ? advance.minRating : undefined,
  }
  // All 11 advance switches are emitted explicitly (false when the bit is
  // clear) so chips / payloads / tests see a deterministic shape.
  for (const item of ALL_ADVANCE_ITEMS) {
    filters[item.key] = (advance.advanceSearch & item.bit) !== 0
  }
  return filters
})

const activeFilterChips = computed<FilterChip[]>(() => filterChips(activeFilters.value))

/**
 * Write a FilterPanel / chip-row edit back into the canonical refs. All 11
 * advance switches (scope + W3 R4-10 higher bits) map onto the
 * AdvanceSearchTable bitmask carried by `advanceOptions.advanceSearch`.
 */
function applyFilters(next: SearchFilters): void {
  selectedCategories.value = maskToIncluded(next.category ?? 0)
  const advanceBits = ALL_ADVANCE_ITEMS.reduce(
    (mask, item) => (next[item.key] ? mask | item.bit : mask),
    0,
  )
  advanceOptions.value = {
    advanceSearch: advanceBits,
    minRating: next.minRating ?? 0,
    pageFrom: next.pageMin ?? 0,
    pageTo: next.pageMax ?? 0,
  }
  sortOrder.value = next.sort ?? 0
}

/** Chip × — drop that one filter and re-run the search. */
function onRemoveFilterChip(chipId: string): void {
  applyFilters(removeFilterChip(activeFilters.value, chipId))
  void runSearch()
}

/** Chip-row Clear — reset every filter and re-run the search. */
function onClearFilters(): void {
  applyFilters({})
  void runSearch()
}

/** FilterPanel primary action — commit with the SearchBar's current text. */
function onFilterPanelSearch(): void {
  filterPanelOpen.value = false
  commitSearch(query.value)
}

// Results.
const contentRef = ref<InstanceType<typeof ContentLayout> | null>(null)
const contentState = ref<'loading' | 'content' | 'empty' | 'error'>('loading')
const refreshing = ref(false)

/* --- W3-F2 (A4): paged single-column list (DownloadView-aligned) ---------
   usePagedList owns page / jump / page window / stale-race guard. fetchPage
   adapts the upstream offset form: usePagedList pages are 1-based while
   `galleryApi.search` is 0-based (the backend then reports
   total = upstream pages × 25). Upstream is fixed at 25 rows/page, so there
   is deliberately no page-size switch (single-tier pageSizes). */
const {
  items: galleries,
  total,
  currentPage,
  totalPages,
  paginationVisible,
  pageWindow,
  jumpInput,
  load: loadPage,
  jumpToPage,
} = usePagedList<GalleryInfo>({
  fetchPage: async (page, size) => {
    const response = await galleryApi.search(
      composedKeyword(),
      categoryParam(),
      page - 1, // 1-based composable page → 0-based upstream page (offset 形态).
      size,
      activeFilters.value,
    )
    return { items: response.data, total: response.total }
  },
  pageSizes: [PAGE_SIZE],
  initialPageSize: PAGE_SIZE,
  fallbackPageSize: PAGE_SIZE,
  onLoadStart: () => {
    // 完全参照下载页：每次非静默加载整页替换，先切 loading 态。
    contentState.value = 'loading'
  },
  onSuccess: (result) => {
    contentState.value = result.items.length === 0 ? 'empty' : 'content'
    contentRef.value?.scrollToTop()
  },
  onError: (error) => {
    console.error('[SearchView] search failed', error)
    // 无内容可展示时落错误态；翻页/刷新失败保留旧页并以 snackbar 提示
    // （usePagedList 的替换语义保证失败不覆盖 items）。
    if (galleries.value.length === 0) {
      contentState.value = 'error'
    } else {
      showSnack('Failed to load this page')
    }
  },
})

// History + quick searches.
const history = ref<string[]>(readStorage<string[]>(HISTORY_KEY) ?? [])
const quickSearches = ref<QuickSearch[]>(readStorage<QuickSearch[]>(QUICK_SEARCH_KEY) ?? [])

// Dialogs + snackbar.
const dialog = ref<'none' | 'manage' | 'save'>('none')
const presetName = ref('')
const snack = ref('')
let snackTimer: number | undefined

const fabExpanded = ref(false)
/** W3-F2 (A4): the list/grid toggle action is retired with the view modes. */
const fabActions: FabAction[] = [
  { id: 'save-quick', icon: 'plus-dark', label: 'Save quick search' },
  { id: 'manage-quick', icon: 'book-open', label: 'Quick searches' },
]

/* ------------------------------ suggestions ----------------------------- */

/**
 * Recent-search cap from `prefs.general.recentSearchMax` — optional-chained
 * because the key ships with Wave-1 1b (A3) and older servers omit it:
 * absent/invalid → 10, 0 → recent searches disabled (MASTER §3.2 B-5).
 */
const recentSearchMax = computed<number>(() => {
  const general = preferencesStore.prefs?.general as
    | (GeneralPreferences & { recentSearchMax?: unknown })
    | undefined
  const raw = general?.recentSearchMax
  if (typeof raw !== 'number' || !Number.isFinite(raw)) return RECENT_SEARCH_DEFAULT_MAX
  return Math.max(0, Math.floor(raw))
})

interface SuggestionEntry {
  suggestion: SearchSuggestion
  kind: 'history' | 'quick'
  preset?: QuickSearch
}

const suggestionEntries = computed<SuggestionEntry[]>(() => {
  const q = query.value.trim().toLowerCase()
  const matches = (text: string | null | undefined): boolean =>
    !q || (text ?? '').toLowerCase().includes(q)
  const cap = recentSearchMax.value
  const fromHistory: SuggestionEntry[] = (cap > 0 ? history.value.slice(0, cap) : [])
    .filter(matches)
    .map((text) => ({ suggestion: { text, hint: 'History' }, kind: 'history' }))
  const fromQuick: SuggestionEntry[] = quickSearches.value
    .filter((preset) => matches(preset.name) || matches(preset.keyword))
    .map((preset) => ({
      suggestion: { text: preset.keyword, hint: preset.name },
      kind: 'quick',
      preset,
    }))
  return [...fromHistory, ...fromQuick].slice(0, 10)
})

const suggestions = computed<SearchSuggestion[]>(() =>
  suggestionEntries.value.map((entry) => entry.suggestion),
)

/* --------------------------- query composition -------------------------- */

/** Positive `selected` → Android SiteConfig exclusion bitmask (bit = excluded). */
function exclusionMask(selected: GalleryCategory[]): number {
  return includedToMask(selected)
}

/** Exclusion bitmask → positive selection (used when loading a preset). */
function maskToSelected(mask: number): GalleryCategory[] {
  return maskToIncluded(mask)
}

/** Android `formatListUrlBuilder` keyword part (uploader:/tag: prefixes). */
function composedKeyword(): string | undefined {
  const q = activeQuery.value.trim()
  if (!q) return undefined
  if (normalSearchMode.value === 'uploader') return `uploader:${q}`
  if (normalSearchMode.value === 'tag') return `tag:${q}`
  return q
}

function categoryParam(): number | undefined {
  const mask = exclusionMask(selectedCategories.value)
  return mask === 0 ? undefined : mask
}

/* -------------------------------- search -------------------------------- */

/** Search/filter commit entry point — always reloads page 1 (loadPage is the
    usePagedList entry; the stale-race guard lives inside the composable). */
function runSearch(): Promise<void> {
  return loadPage(1)
}

/** Commit a search: record history, collapse the input, reload page 1. */
function commitSearch(raw: string | null | undefined): void {
  const q = (raw ?? '').trim()
  activeQuery.value = q
  if (q) addHistory(q)
  searchBarState.value = 'normal'
  searchTitle.value = q || 'Search'
  filterPanelOpen.value = false
  fabExpanded.value = false
  void runSearch()
}

/** Pull-to-refresh — silent reload keeps the current list visible (the
    refreshing header parks via v-model; ContentLayout's spinner is not used). */
async function onRefresh(): Promise<void> {
  await loadPage(1, { silent: true })
  refreshing.value = false
}

/* ------------------------- AppListRow click zones ------------------------ */

/** 缩略图点击 → 详情页（AppListRow `open` 分区）。 */
function openGallery(gid: number): void {
  router.push(`/gallery/${gid}`)
}

/** 行主体点击 → 直接进统一阅读器（AppListRow `read` 分区，下载页同语义）。 */
function openReader(gid: number): void {
  router.push(`/reader/${gid}`)
}

/* ----------------------------- row presentation -------------------------- */

/** 行视图模型：画廊行 + 类目 chip（未知 bit 不渲染 chip，与 GalleryCard 同）。 */
const rows = computed(() =>
  galleries.value.map((gallery) => ({
    gallery,
    chip: CATEGORY_BY_BIT[gallery.category],
  })),
)

/**
 * 行展示标题——脱敏在本视图完成（AppListRow 契约：消费方传入
 * `maskedTitle(title‖titleJpn‖'Untitled')`，组件本体不碰隐私逻辑）。
 */
function displayTitle(gallery: GalleryInfo): string {
  return maskedTitle(gallery.title || gallery.titleJpn || 'Untitled', gallery.gid)
}

/** 副题（日文标题）：打码开启时隐藏（GalleryCard title-jpn 同一门控）。 */
function displaySubtitle(gallery: GalleryInfo): string | null {
  if (privacyMaskEnabled.value) return null
  return gallery.titleJpn || null
}

/**
 * General-preference keys consumed by the row but still optional in the
 * typed DTO (mirrors GalleryCard's defensive read — absent key hides the
 * field).
 */
interface GeneralPrefsExtras {
  showUploader?: boolean
  showPostedTime?: boolean
}

const generalPrefs = computed<(GeneralPreferences & GeneralPrefsExtras) | undefined>(
  () => preferencesStore.prefs?.general,
)

/** B-2 info switches — strictly `true` shows the field; anything else hides it.
    隐私打码：上传者属敏感内容，一律隐藏（GalleryCard 同语义）。 */
function showUploader(gallery: GalleryInfo): boolean {
  return (
    generalPrefs.value?.showUploader === true &&
    !privacyMaskEnabled.value &&
    Boolean(gallery.uploader)
  )
}

function showPostedTime(gallery: GalleryInfo): boolean {
  return generalPrefs.value?.showPostedTime === true && Boolean(gallery.posted)
}

/** 隐私打码：标签是内容关键词，一律隐藏（GalleryCard 同语义）。 */
function rowTags(gallery: GalleryInfo): string[] {
  return privacyMaskEnabled.value ? [] : (gallery.simpleTags ?? [])
}

/**
 * W5 (plan-2026-09-02) — 阅读进度角标：`general.showReadProgress` 开启且
 * `readProgress > 0` 才显示（`N/MP`，页数未知退化 `NP`），顶替页数文案。
 */
function readProgressLabel(gallery: GalleryInfo): string {
  const progress = gallery.readProgress
  if (typeof progress !== 'number' || !Number.isFinite(progress) || progress <= 0) return ''
  const current = progress + 1
  return gallery.pages > 0 ? `${current}/${gallery.pages}P` : `${current}P`
}

function showReadProgressBadge(gallery: GalleryInfo): boolean {
  return generalPrefs.value?.showReadProgress === true && readProgressLabel(gallery) !== ''
}

/* --------------------------- SearchBar handlers -------------------------- */

function openSearch(): void {
  // Android: tapping the title focuses the edit text and shows the list.
  searchBarState.value = 'search-list'
}

function closeSearch(): void {
  searchBarState.value = 'normal'
  searchTitle.value = activeQuery.value || 'Search'
}

function onQueryInput(next: string): void {
  query.value = next
  if (searchBarState.value === 'search') {
    searchBarState.value = 'search-list'
  }
}

function onSelectSuggestion(_suggestion: SearchSuggestion, index: number): void {
  const entry = suggestionEntries.value[index]
  if (!entry) return
  if (entry.kind === 'quick' && entry.preset) {
    loadQuickSearch(entry.preset)
  } else {
    query.value = entry.suggestion.text
    commitSearch(entry.suggestion.text)
  }
}

/** Long-press a suggestion — deletes history entries (Android onLongClick). */
function onDismissSuggestion(_suggestion: SearchSuggestion, index: number): void {
  const entry = suggestionEntries.value[index]
  if (entry?.kind !== 'history') return
  history.value = history.value.filter((item) => item !== entry.suggestion.text)
  writeStorage(HISTORY_KEY, history.value)
  showSnack('Removed from history')
}

/* ------------------------------ history/presets -------------------------- */

/** Device-local recent searches; capped by `prefs.general.recentSearchMax` (0 = off). */
function addHistory(keyword: string): void {
  const cap = recentSearchMax.value
  if (cap <= 0) return
  history.value = [keyword, ...history.value.filter((item) => item !== keyword)].slice(0, cap)
  writeStorage(HISTORY_KEY, history.value)
}

function loadQuickSearch(preset: QuickSearch): void {
  query.value = preset.keyword ?? ''
  normalSearchMode.value = NUM_TO_MODE[preset.mode] ?? 'normal'
  selectedCategories.value = maskToSelected(preset.category)
  advanceOptions.value = {
    advanceSearch: preset.advanceSearch,
    minRating: preset.minRating,
    pageFrom: preset.pageFrom,
    pageTo: preset.pageTo,
  }
  // W3 R4-11: presets persist the sort order (contracts QuickSearchDto.sort);
  // legacy rows / pre-W3 device presets read absent → default order.
  const sort = Math.floor(preset.sort ?? 0)
  sortOrder.value = (sort >= 0 && sort <= 3 ? sort : 0) as SearchSortOrder
  dialog.value = 'none'
  commitSearch(preset.keyword)
}

function openSaveDialog(): void {
  presetName.value = ''
  dialog.value = 'save'
}

/**
 * Save the current filter state as a quick-search preset — the payload is
 * built in the exact QuickSearchDto schema and POSTed to
 * `/api/v1/gallery/quick-search` (Wave-1 1a, task A5). When the server is
 * unreachable the preset degrades to device-local storage so the single-user
 * LAN app keeps working offline.
 */
async function saveQuickSearch(): Promise<void> {
  const name = presetName.value.trim()
  if (!name) {
    showSnack('Give the preset a name first')
    return
  }
  const payload = filtersToQuickSearchPayload(activeFilters.value, {
    name,
    keyword: query.value.trim(),
    mode: MODE_TO_NUM[normalSearchMode.value],
  })
  try {
    const created = await galleryApi.createQuickSearch(payload)
    quickSearches.value = [...quickSearches.value, created]
    writeStorage(QUICK_SEARCH_KEY, quickSearches.value)
    showSnack(`Saved “${name}”`)
  } catch (error) {
    console.error('[SearchView] quick-search POST failed, saving locally', error)
    const localPreset: QuickSearch = { id: Date.now(), ...payload }
    quickSearches.value = [...quickSearches.value, localPreset]
    writeStorage(QUICK_SEARCH_KEY, quickSearches.value)
    showSnack(`Saved “${name}” on this device (server unreachable)`)
  }
  dialog.value = 'none'
}

/**
 * Delete a quick-search preset — W3 R4-12: the deletion is sent to the
 * server (`DELETE /gallery/quick-search/{id}`) and applied locally. When the
 * server is unreachable the preset is still removed on this device (the
 * offline-first LAN semantics mirror the save fallback); a locally-only
 * deletion of a server preset can reappear if the list is reseeded later.
 */
async function deleteQuickSearch(id: number): Promise<void> {
  quickSearches.value = quickSearches.value.filter((preset) => preset.id !== id)
  writeStorage(QUICK_SEARCH_KEY, quickSearches.value)
  try {
    await galleryApi.deleteQuickSearch(id)
  } catch (error) {
    console.error('[SearchView] quick-search DELETE failed (removed locally)', error)
    showSnack('Server unreachable — preset removed on this device only')
  }
}

function modeLabel(mode: number): string {
  return MODE_LABELS[NUM_TO_MODE[mode] ?? 'normal']
}

/* ---------------------------------- FAB ---------------------------------- */

function onFabPrimary(): void {
  // W3 R4-10: the primary FAB opens the FilterPanel — the single filter
  // surface (the legacy search panel is retired).
  filterPanelOpen.value = true
  contentRef.value?.scrollToTop()
}

function onFabSecondary(action: FabAction): void {
  fabExpanded.value = false
  if (action.id === 'save-quick') {
    openSaveDialog()
  } else if (action.id === 'manage-quick') {
    dialog.value = 'manage'
  }
}

/* ------------------- PC keyboard shortcuts (Wave-1 1a) ------------------- */

/** True when the event target takes typing — shortcuts must not fire there. */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable
}

/** `/` focuses the search box, `f` toggles the filter panel. */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.ctrlKey || event.metaKey || event.altKey) return
  if (isEditableTarget(event.target)) return
  if (event.key === '/') {
    event.preventDefault()
    focusSearch()
  } else if (event.key === 'f' || event.key === 'F') {
    event.preventDefault()
    filterPanelOpen.value = !filterPanelOpen.value
  }
}

/** Enter the search state (if needed) and focus the edit text. */
function focusSearch(): void {
  if (searchBarState.value === 'normal') {
    searchBarState.value = 'search-list'
  }
  void nextTick(() => searchBarRef.value?.focusInput())
}

/* --------------------------------- chrome -------------------------------- */

function onNavSelect(item: NavItem): void {
  router.push(NAV_ROUTES[item.id] ?? '/')
}

function showSnack(message: string): void {
  snack.value = message
  if (snackTimer) window.clearTimeout(snackTimer)
  snackTimer = window.setTimeout(() => {
    snack.value = ''
  }, 2600)
}

/* -------------------------------- storage -------------------------------- */

function readStorage<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

function writeStorage(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Storage unavailable (privacy mode) — feature degrades silently.
  }
}

/* --------------------------------- boot ---------------------------------- */

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)
  // Recent-search cap lives in preferences (loaded lazily; best effort here —
  // the store reports its own errors and the cap defaults to 10 until ready).
  if (!preferencesStore.prefs) {
    void preferencesStore.load()
  }
  // Seed presets from the server the first time (Android QuickSearch table).
  if (quickSearches.value.length === 0) {
    try {
      const response = await galleryApi.getQuickSearches()
      if (response.success && response.data?.length) {
        quickSearches.value = response.data
        writeStorage(QUICK_SEARCH_KEY, quickSearches.value)
      }
    } catch {
      // Offline seed is best-effort; local presets still work.
    }
  }
  await loadPage(1)
})

/* KeepAlive guard (audit P1-5, W1-F3): App.vue caches SearchView, so a
   deactivated instance must drop the window listener or it keeps hijacking
   `/` / `f` while another view sits in front. remove-before-add keeps exactly
   one listener across mount → activate cycles; the onMounted add above also
   covers mounts that bypass KeepAlive (onActivated only fires within it).
   W3-F2 note: PageUp/PageDown keyboard paging (DownloadView) is deliberately
   NOT enabled here — usePagedList would register an unguarded window listener
   (mounted → unmounted only) and a deactivated cached instance would swallow
   the keys in the background, the exact bug class P1-5 fixed. */
onActivated(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
  window.addEventListener('keydown', onGlobalKeydown)
})

onDeactivated(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  // Cache-evicted instances unmount without a deactivate pass.
  window.removeEventListener('keydown', onGlobalKeydown)
  if (snackTimer) window.clearTimeout(snackTimer)
})
</script>

<style scoped>
/* Scene shell — horizontal flex so the persistent drawer (≥720px, static
   panel) sits beside the main column; below that the drawer overlays. */
.search-scene {
  display: flex;
  height: 100dvh;
  background: var(--color-bg);
  overflow: hidden;
}

.search-scene__main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  /* The SearchBar sits in normal flow at the head of this column, so the
     whole column (bar → filter panel → results bar → ContentLayout) clears
     the status bar / cutout together. The drawer sibling already carries its
     own safe-area padding — do not pad `.search-scene` itself. The results
     bottom clears the FAB cluster via --gallery-padding-bottom-fab, which
     already includes --safe-area-bottom (no double offset). */
  padding-top: var(--safe-area-top);
}

.search-scene__content {
  flex: 1 1 auto;
  min-height: 0;
}

/* Positioning anchor for the FilterPanel popover (Wave-1 1a). */
.search-scene__filter-anchor {
  position: relative;
}

/* ------------------------------ pagination bar --------------------------- */
/* 参照下载页分页条（label-tabs 样式语言）：页码窗口 / 前后页 / 跳页。
   W3-F2 (A4)：上游固定 25 条/页，无条数切换下拉。 */
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

/* -------------------------------- results -------------------------------- */

/* 单列密信息行（A4）：AppListRow 纵向堆叠，行骨架（卡片面/缩略图/标题）
   由共享组件自带；这里只管容器留白与 FAB 避让。 */
.results {
  padding: var(--gallery-list-margin-v) var(--gallery-list-margin-h)
    var(--gallery-padding-bottom-fab);
}

/* ------------------------- search row info bits -------------------------- */

.search-item__pages,
.search-item__read-progress {
  flex-shrink: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.search-item__extra {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px var(--spacing);
  min-width: 0;
  font-size: var(--text-super-small); /* 12sp */
  color: var(--text-color-secondary);
}

.search-item__uploader,
.search-item__posted {
  white-space: nowrap;
}

.search-item__tag {
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--color-surface);
  white-space: nowrap;
}

/* -------------------------------- dialogs -------------------------------- */

.dialog-scrim {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--black-overlay);
}

.dialog {
  width: min(420px, 100%);
  max-height: min(80dvh, 560px);
  overflow-y: auto;
  padding: 20px 20px 12px;
  border-radius: var(--card-radius);
  background: var(--color-background-floating);
  box-shadow: 0 8px 24px var(--shadow-color);
}

.dialog__title {
  margin: 0 0 12px;
  font-size: clamp(16px, 18px, 22px);
  font-weight: 700;
  color: var(--text-color-primary);
}

.dialog__summary {
  margin: 0 0 14px;
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
}

.dialog__empty {
  margin: 8px 0 16px;
  font-size: clamp(13px, 14px, 16px);
  color: var(--text-color-secondary);
}

.dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--color-divider);
}

.dialog-enter-active,
.dialog-leave-active {
  transition: opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dialog-enter-active .dialog,
.dialog-leave-active .dialog {
  transition:
    transform var(--duration-scene-translate) var(--ease-decelerate-quint),
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart);
}

.dialog-enter-from,
.dialog-leave-to {
  opacity: 0;
}

.dialog-enter-from .dialog,
.dialog-leave-to .dialog {
  transform: translateY(16px) scale(0.97);
  opacity: 0;
}

/* Quick-search rows. */
.qs-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.qs-item {
  display: flex;
  align-items: center;
  gap: 4px;
  border-radius: var(--card-radius);
  transition: background-color 120ms var(--ease-decelerate-quart);
}

.qs-item:hover {
  background: var(--color-surface);
}

.qs-item + .qs-item {
  border-top: 1px solid var(--color-divider);
}

.qs-item__main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 8px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.qs-item__name {
  font-size: clamp(14px, 16px, 18px);
  font-weight: 600;
  color: var(--text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qs-item__summary {
  font-size: clamp(11px, 12px, 14px);
  color: var(--text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qs-item__delete {
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--drawable-color-secondary);
  cursor: pointer;
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    color 150ms var(--ease-decelerate-quart);
}

.qs-item__delete:hover {
  background: var(--color-surface-activated);
  color: var(--color-red-500);
}

/* ------------------------------ form + buttons --------------------------- */

.field {
  position: relative;
  display: block;
}

.field input {
  width: 100%;
  padding: 14px 12px 10px;
  border: 1px solid var(--color-divider);
  border-radius: var(--card-radius);
  background: transparent;
  font-size: clamp(14px, 16px, 18px);
  color: var(--text-color-primary);
  outline: none;
  transition: border-color 150ms var(--ease-decelerate-quart);
}

.field input:focus {
  border-color: var(--color-primary);
}

.field__label {
  position: absolute;
  left: 10px;
  top: 50%;
  translate: 0 -50%;
  padding: 0 4px;
  font-size: clamp(14px, 16px, 18px);
  color: var(--text-color-secondary);
  pointer-events: none;
  transition:
    top 150ms var(--ease-decelerate-quart),
    font-size 150ms var(--ease-decelerate-quart),
    color 150ms var(--ease-decelerate-quart);
}

.field input:focus + .field__label,
.field input:not(:placeholder-shown) + .field__label {
  top: 0;
  font-size: clamp(10px, 12px, 13px);
  color: var(--color-primary);
  background: var(--color-background-floating);
}

.btn-primary {
  padding: 9px 22px;
  border: none;
  border-radius: var(--card-radius);
  background: var(--color-primary);
  color: var(--color-white);
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow: 0 1px 3px var(--shadow-color);
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.btn-primary:hover {
  background: var(--color-primary-dark);
}

.btn-primary:active {
  transform: scale(0.97);
}

.btn-text {
  padding: 9px 14px;
  border: none;
  border-radius: var(--card-radius);
  background: transparent;
  color: var(--text-color-theme-primary);
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  cursor: pointer;
  transition: background-color 150ms var(--ease-decelerate-quart);
}

.btn-text:hover {
  background: var(--color-surface);
}

/* -------------------------------- snackbar ------------------------------- */

.snackbar {
  position: fixed;
  left: 50%;
  /* The FAB cluster below is offset by --safe-area-bottom (FabLayout), so
     the snack carries the same inset to stay clear of both the FABs and the
     home indicator. */
  bottom: calc(var(--corner-fab-margin) + var(--fab-size) + 16px + var(--safe-area-bottom));
  translate: -50% 0;
  z-index: 300;
  max-width: min(480px, calc(100vw - 32px));
  padding: 12px 20px;
  border-radius: var(--card-radius);
  background: var(--gallery-slider-background);
  color: var(--color-white);
  font-size: clamp(13px, 14px, 16px);
  box-shadow: 0 4px 12px var(--shadow-color);
}

.snack-enter-active,
.snack-leave-active {
  transition:
    opacity var(--duration-scene-opacity) var(--ease-decelerate-quart),
    translate var(--duration-scene-translate) var(--ease-decelerate-quint);
}

.snack-enter-from,
.snack-leave-to {
  opacity: 0;
  translate: -50% 12px;
}

@media (prefers-reduced-motion: reduce) {
  .dialog-enter-active .dialog,
  .dialog-leave-active .dialog,
  .snack-enter-active,
  .snack-leave-active {
    transition: none;
  }
}
</style>
