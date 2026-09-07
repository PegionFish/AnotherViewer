import client from './client'

/**
 * WebUI 通用偏好。A4 收尾后 Web 不再消费 `general.listMode`（后端字段保留，
 * 仍由 App 端同步）；GET 返回的原始对象按原样透传 PUT，键值不受影响。
 */
export interface GeneralPreferences {
  theme: string
  themeAutoSwitch: boolean
  launchPage: string
  showReadProgress: boolean
  detailSize: string
  thumbSize: string
  historyInfoSize: number
  showJpnTitle: boolean
  showGalleryPages: boolean
  showTagTranslations: boolean
  showGalleryComment: boolean
  showGalleryRating: boolean
  showEhEvents: boolean
  showEhLimits: boolean
  /** Wave-1 B-2: 画廊卡片显示上传者 */
  showUploader: boolean
  /** Wave-1 B-2: 画廊卡片显示发布时间 */
  showPostedTime: boolean
  /** Wave-1 B-3: 默认收藏槽，clamp -2..9 */
  defaultFavoriteSlot: number
  /** Wave-1 B-4: `|` 分隔的 10 个收藏槽名，空项回退默认 */
  favoriteSlotNames: string
  /** Wave-1 B-5: 最近搜索保留条数，0 = 关闭 */
  recentSearchMax: number
}

export interface ReaderPreferences {
  readingDirection: string
  pageMode: string
  firstPageCover: boolean
  pageScaling: string
  startPosition: string
  autoPlayIntervalSec: number
  showProgress: boolean
  showPageInterval: boolean
  fullscreen: boolean
  brightness: number
  /** Wave-1 A: black|gray|white */
  backgroundColor: string
  /** Wave-1 A: threeZone|edgeOnly|disabled */
  tapZoneScheme: string
  /** Wave-1 A: 键盘翻页 */
  keyboardPaging: boolean
  /** Wave-1 A: 加法缩放步进（倍率，0.05–1；键盘/面板每次 ± 的步长） */
  zoomStep: number
  /** Wave-1 A: 最大缩放（1–5，与阅读器捏合/双击的上限共用） */
  maxZoom: number
  /** Wave-1 A: 双页间距 px（≥0） */
  dualPageGap: number
  /** Wave-1 A: 拆分宽页 */
  splitWidePages: boolean
  /** Wave-1 A: 预加载页数（≥0） */
  preloadCount: number
  /** Wave-1 A: slide|fade|none */
  pageTransition: string
}

export interface PrivacyPreferences {
  enableAnalytics: boolean
}

export interface Preferences {
  general: GeneralPreferences
  reader: ReaderPreferences
  privacy: PrivacyPreferences
}

/**
 * 与后端 PreferenceDto.kt（PreferenceResponse）保持一致的缺省值 ——
 * Wave-1 新键加入后同步更新；测试夹具与回退逻辑共用。
 */
export const DEFAULT_GENERAL_PREFERENCES: GeneralPreferences = {
  theme: 'light',
  themeAutoSwitch: false,
  launchPage: 'homepage',
  showReadProgress: true,
  detailSize: 'long',
  thumbSize: 'middle',
  historyInfoSize: 100,
  showJpnTitle: false,
  showGalleryPages: false,
  showTagTranslations: true,
  showGalleryComment: true,
  showGalleryRating: true,
  showEhEvents: true,
  showEhLimits: true,
  showUploader: false,
  showPostedTime: false,
  defaultFavoriteSlot: 0,
  favoriteSlotNames: '',
  recentSearchMax: 10,
}

export const DEFAULT_READER_PREFERENCES: ReaderPreferences = {
  readingDirection: 'rtl',
  // 'auto'（非 'dual'）：竖屏手机单页全屏适应、横屏才并排双页。固定 'dual'
  // 会让竖屏每页只有半屏宽，用户被迫逐页手动缩放。
  pageMode: 'auto',
  firstPageCover: true,
  pageScaling: 'fit',
  startPosition: 'top_right',
  autoPlayIntervalSec: 2,
  showProgress: true,
  showPageInterval: true,
  // false 才能维持既有行为（进入阅读器 chrome 可见）：消费侧是
  // 「chromeVisible 初值 = !fullscreen」，默认 true 会让 chrome 一进就藏。
  fullscreen: false,
  brightness: 0,
  backgroundColor: 'black',
  tapZoneScheme: 'threeZone',
  keyboardPaging: true,
  // 加法步进语义（非乘法倍率）：与既有 READER_ZOOM_STEP 常量同值，存量行为不变。
  zoomStep: 0.25,
  // 与阅读器既有 READER_ZOOM_MAX 常量同值，存量行为不变。
  maxZoom: 3,
  dualPageGap: 8,
  splitWidePages: false,
  preloadCount: 2,
  pageTransition: 'slide',
}

export const DEFAULT_PRIVACY_PREFERENCES: PrivacyPreferences = {
  enableAnalytics: true,
}

export const DEFAULT_PREFERENCES: Preferences = {
  general: DEFAULT_GENERAL_PREFERENCES,
  reader: DEFAULT_READER_PREFERENCES,
  privacy: DEFAULT_PRIVACY_PREFERENCES,
}

export const preferencesApi = {
  async get(): Promise<Preferences> {
    const { data } = await client.get('/preferences')
    return data
  },
  async update(prefs: Partial<Preferences>): Promise<Preferences> {
    const { data } = await client.put('/preferences', prefs)
    return data
  },
}
