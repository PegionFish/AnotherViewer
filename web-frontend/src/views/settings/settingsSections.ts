/**
 * settingsSections.ts — 合并后设置面板的分组导航表（A5-1 单一事实源）。
 *
 * 「偏好」= 原 /settings 四页（用户偏好，preferencesApi）；
 * 「服务器」= 原 /admin 十页（服务器配置，settingsApi/backupApi 等），
 * 路径整体迁至 /settings/server/*（旧 /admin/* 由路由表 redirect 兜底）。
 * SettingsLayout（宽屏分组侧栏）与 SettingsIndex（窄屏分组索引页）共用
 * 本表，两侧永不漂移。
 */
export interface SettingsSectionItem {
  /** 完整目标路径（router-link 直接消费）。 */
  path: string
  /** 展示标签。 */
  label: string
  /** AppIcon 注册表图标名（沿用原 SettingsLayout/AdminLayout 的选型）。 */
  icon: string
}

export interface SettingsSectionGroup {
  /** 分组标题（偏好 / 服务器）。 */
  label: string
  items: SettingsSectionItem[]
}

export const SETTINGS_GROUPS: SettingsSectionGroup[] = [
  {
    label: '偏好',
    items: [
      { path: '/settings/general', label: '通用', icon: 'settings-dark' },
      { path: '/settings/reader', label: '阅读器', icon: 'book-open-primary' },
      { path: '/settings/privacy', label: '隐私', icon: 'sec-primary' },
      { path: '/settings/transfer', label: '传输', icon: 'send-dark' },
    ],
  },
  {
    label: '服务器',
    items: [
      { path: '/settings/server/download', label: '下载', icon: 'download-dark' },
      { path: '/settings/server/filter-slots', label: '筛选槽位', icon: 'magnify-dark' },
      { path: '/settings/server/server', label: '服务器', icon: 'settings-dark' },
      { path: '/settings/server/backup', label: '备份', icon: 'download-box-dark' },
      { path: '/settings/server/devices', label: '设备', icon: 'mobile-hand-left' },
      { path: '/settings/server/eh', label: 'EH 会话', icon: 'cookie-brown' },
      { path: '/settings/server/access', label: '访问', icon: 'sec-primary' },
      { path: '/settings/server/processing', label: '图像处理', icon: 'similar-primary' },
      { path: '/settings/server/advanced', label: '高级', icon: 'dots-vertical-secondary-dark' },
      { path: '/settings/server/about', label: '关于', icon: 'info-dark' },
    ],
  },
]

/** 宽屏（双栏 two-pane）断点：与 SettingsLayout CSS 的 960px 媒体查询一致。 */
export const SETTINGS_WIDE_QUERY = '(min-width: 960px)'

/** 宽屏 /settings exact 的默认子页（原路由级 redirect 的接棒者）。 */
export const SETTINGS_DEFAULT_SUBPATH = '/settings/general'
