import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

export const routes: RouteRecordRaw[] = [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/',
      name: 'Home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/gallery/:gid',
      name: 'GalleryDetail',
      component: () => import('@/views/GalleryDetailView.vue'),
      props: true,
    },
    {
      path: '/reader/:gid/:page?',
      name: 'Reader',
      component: () => import('@/views/ReaderView.vue'),
      props: true,
    },
    {
      path: '/favorites',
      name: 'Favorites',
      component: () => import('@/views/FavoriteView.vue'),
    },
    {
      path: '/history',
      name: 'History',
      component: () => import('@/views/HistoryView.vue'),
    },
    {
      path: '/downloads',
      name: 'Downloads',
      component: () => import('@/views/DownloadView.vue'),
    },
    {
      path: '/search',
      name: 'Search',
      component: () => import('@/views/SearchView.vue'),
    },
    /**
     * A5-1: 统一设置面板 —— 原 /settings（偏好，preferencesApi）与 /admin
     * （服务器，settingsApi/backupApi 等）双面板合并。分组：
     *   偏好   = general / reader / privacy / transfer（路径不变）
     *   服务器 = 原 /admin 十页，整体迁至 /settings/server/*
     * `/settings` exact 不再路由级 redirect：窄屏渲染分组索引页
     * （SettingsIndex），宽屏由其 setup 内 matchMedia 一次性检查后 replace
     * 到默认子页。旧 /admin/* 全部 redirect 兜底（见下方记录）。
     */
    {
      path: '/settings',
      component: () => import('@/views/settings/SettingsLayout.vue'),
      children: [
        { path: '', name: 'SettingsIndex', component: () => import('@/views/settings/SettingsIndex.vue') },
        { path: 'general', name: 'SettingsGeneral', component: () => import('@/views/settings/GeneralSettings.vue') },
        { path: 'reader', name: 'SettingsReader', component: () => import('@/views/settings/ReaderSettings.vue') },
        { path: 'privacy', name: 'SettingsPrivacy', component: () => import('@/views/settings/PrivacySettings.vue') },
        { path: 'transfer', name: 'SettingsTransfer', component: () => import('@/views/settings/TransferSettings.vue') },
        { path: 'server/download', name: 'SettingsServerDownload', component: () => import('@/views/admin/AdminDownload.vue') },
        { path: 'server/filter-slots', name: 'SettingsServerFilterSlots', component: () => import('@/views/admin/AdminFilterSlots.vue') },
        { path: 'server/server', name: 'SettingsServerServer', component: () => import('@/views/admin/AdminServer.vue') },
        { path: 'server/backup', name: 'SettingsServerBackup', component: () => import('@/views/admin/AdminBackup.vue') },
        { path: 'server/devices', name: 'SettingsServerDevices', component: () => import('@/views/admin/AdminDevices.vue') },
        { path: 'server/eh', name: 'SettingsServerEh', component: () => import('@/views/admin/AdminEhSession.vue') },
        { path: 'server/access', name: 'SettingsServerAccess', component: () => import('@/views/admin/AdminAccess.vue') },
        { path: 'server/processing', name: 'SettingsServerProcessing', component: () => import('@/views/admin/AdminProcessing.vue') },
        { path: 'server/advanced', name: 'SettingsServerAdvanced', component: () => import('@/views/admin/AdminAdvanced.vue') },
        { path: 'server/about', name: 'SettingsServerAbout', component: () => import('@/views/admin/AdminAbout.vue') },
      ],
    },
    // A5-1: 旧 /admin 深链兜底（含 HomeView 的 /admin/eh EH 会话跳转）。
    { path: '/admin', redirect: '/settings/server/download' },
    { path: '/admin/download', redirect: '/settings/server/download' },
    { path: '/admin/filter-slots', redirect: '/settings/server/filter-slots' },
    { path: '/admin/server', redirect: '/settings/server/server' },
    { path: '/admin/backup', redirect: '/settings/server/backup' },
    { path: '/admin/devices', redirect: '/settings/server/devices' },
    { path: '/admin/eh', redirect: '/settings/server/eh' },
    { path: '/admin/access', redirect: '/settings/server/access' },
    { path: '/admin/processing', redirect: '/settings/server/processing' },
    { path: '/admin/advanced', redirect: '/settings/server/advanced' },
    { path: '/admin/about', redirect: '/settings/server/about' },
    {
      path: '/smb-backup',
      name: 'SmbBackup',
      component: () => import('@/views/SmbBackupView.vue'),
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to, _from, next) => {
  if (to.name === 'Login') {
    next()
    return
  }
  // 检查服务器是否要求登录
  const token = localStorage.getItem('token')
  if (token) {
    next()
    return
  }
  // 无 token 时查询服务器是否需要认证
  try {
    const { authApi } = await import('@/api/auth')
    const status = await authApi.status()
    if (!status.authRequired) {
      next() // 服务器不要求登录，放行
      return
    }
  } catch {
    // 服务器不可达，走正常登录流程
  }
  next({ name: 'Login' })
})

export default router
