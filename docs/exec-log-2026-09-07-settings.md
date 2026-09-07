# 执行日志：设置项梳理去重 + 可用性接线批次（2026-09-07）

方案与审查结论见 [plan-2026-09-07-settings-dedup.md](plan-2026-09-07-settings-dedup.md)。
本批次按 6.4 处置总纲实施，多代理按文件域并行（5 个代理），全部完成后统一验收。

## 一、落地内容（F1-F10 全部）

### 去重/死项清理（第一轮 F 系列）
- **F1** AdminAdvanced 删「导出数据/导入数据」（备份页同 API 子集且护栏弱），
  保留「清除本地数据」；restorePending 横幅与 backup/jobs 依赖随之移除。
- **F2** AdminServer 的 SMB 死开关改只读状态行：`smbApi.getConfig()` 读真实
  enabled（null body=已停用，请求失败=「状态未知」静默降级）；「前往备份页面」
  改常驻行。
- **F3** workerCount 死旋钮全链删除：前端行+类型+spec 夹具、后端
  SettingsDto/SettingsService、contracts/openapi.yaml。SiteCoreConfigProperties
  的 yml 绑定字段未动（纯配置管线）。
- **F4** AdminAdvanced 删「界面语言」「保存解析错误日志」（两端无功能）。
- **F5** 内容打码模式迁至 /settings/privacy（启用统计上方），乐观切换+失败回滚
  逻辑原样迁移，不走 preferencesStore。
- **F6** 「服务器>服务器」改名「缓存与存储」（页题+settingsSections 标签，路径不动）。

### 后端持久化/校验（F7/F8/F10）
- **F7** 下载数值组落盘：新增 `KEY_DOWNLOAD_DELAY/KEY_DOWNLOAD_TIMEOUT/
  KEY_MAX_CONCURRENT_GALLERIES/KEY_MAX_CONCURRENT_IMAGES/KEY_CACHE_SIZE_MB`
  五键，updateSettings 补 serverConfig.set；SiteDataDirInitializer 启动回喂
  （trim/toXxxOrNull 防御，空白/非数字跳过保 yml 默认）。
- **F8** maxConcurrentGalleries 运行时生效：DownloadService.applyGalleryConcurrency
  （clamp 1-20，先 max 后 core 防IllegalArgumentException，等值跳过）；
  SettingsService 落盘后调用、Initializer 回喂后对齐池容量（否则重启后仍取
  构造期值）。
- **F10** PreferenceDto 校验：App 共享键只加下限（historyInfoSize/autoPlayIntervalSec
  @Min(1)、brightness 0-100）；Web 本地键收紧（zoomStep 0.05-1.0、maxZoom 1-5、
  dualPageGap ≤100、preloadCount ≤20）。App 同步走 /sync/push 原样存储不经
  bean 校验，不受影响（DTO 注释已记）。

### 偏好接线（Web 端消费补齐）
- **详情页**：showGalleryPages/showGalleryRating/showGalleryComment 三开关 v-if
  （评论 GET 不跳过，幂等无害）；详情页深链自预热偏好。
- **showJpnTitle**：五处日文副题收敛到 utils/jpnSubtitle.ts——prefs 未加载时
  显示防闪失，加载后严格 ===true，打码守卫留在调用方。
- **阅读器六键**：firstPageCover（spread 换算带参化，false 时首两页并摊）、
  showProgress（状态栏页码行 prop 门控）、showPageInterval（滑轨 N-1 刻度点，
  分页模式+偏好开）、fullscreen（chromeVisible = !prefs.fullscreen，不接
  Fullscreen API）、autoPlayIntervalSec（播放器初始/重置取偏好 + 快捷面板
  chips 写回，三方归一）、startPosition（见决策表——App 原义是缩放锚角）。
- **zoom 组**：zoomStep 重定义为加法步进（默认 0.25，0.05-1）；maxZoom 默认 3
  范围 1-5（键盘/捏合/双击终点/快捷面板活消费）；dualPageGap 补
  `column-gap: var(--reader-dual-gap)`；splitWidePages 双页模式下宽比>1.2
  逐槽位拆分（img 200% 宽偏移，页码/进度语义不变）。
- **theme 双源修复**：preferences load 回灌 themeStore（防回环守卫）；
  setTheme 写穿 updateGeneral（toggleTheme 随之写穿）；themeAutoSwitch 接管
  系统跟随闸门（未加载回退旧 localStorage 语义）。
- **加载时序**：DownloadView/HistoryView 深链直入补 preferencesStore.load
  （HomeView 同款守卫），showReadProgress 角标不再按空 prefs 默认隐藏。

## 二、关键默认决策（实施时定案）

| 议题 | 决策 | 依据 |
| --- | --- | --- |
| zoomStep 语义 | 乘法废弃，改加法步进 0.05-1 默认 0.25 | 旧乘法默认 1.5 与阅读器 0.25 加法现实冲突且从未被消费 |
| startPosition | 平移锚角（图片溢出视口时的初始对齐角），非起始页 | App 语义考据：ImageView.setScaleOffset dst 锚角（ImageView.java:426-447）；与 Web 阅读进度恢复不冲突 |
| fullscreen 默认 | 前端默认 false，服务器值（true）生效 → 阅读器默认隐藏工具栏进入 | 与 Android reading_fullscreen 默认 true 对齐（Android 是体验基准）；点按呼出不变 |
| showGalleryPages 默认 | 按协议 false（非任务书假设的 true） | api 默认与后端 DTO 即 false |
| splitWidePages | 逐槽位拆分不重排配对 | 重排需预知每页比例，违背最小实现 |
| brightness @Max(100) | 照做并注释坑 | App 实际 0-200，但 App 同步不经该校验；WebUI 值域收窄 |
| SMB 未知态 | null body=已停用；请求失败才=状态未知 | 后端 getConfig 语义核对 |
| 评论 v-if 层级 | 渲染层门控，GET 不跳过 | 时序竞争补偿 watch 复杂度不值；幂等 GET 无副作用 |

## 三、验证

- 前端：vitest **91 文件 1105 用例全绿**；vue-tsc 干净。
- 后端：`:anotherviewer-web:test` **977 用例全绿**（新增 SettingsServiceTest 3、
  SiteDataDirInitializerTest 2、DownloadService 并发池用例；DTO/Controller
  校验用例重写）。
- 视觉基线：`test:visual:update` 重拍 229 屏，compare **229/229 通过**。
  漂移 108 张，构成：admin-server/admin-download/admin-advanced/settings 索引
  全屏幅（真内容变更）+ 全部宽屏屏幅（侧栏标签「缓存与存储」连锁）+ reader 12 张
  （上一批遗留改动被重拍固化）。阅读器空态（无后端）不出现刻度/工具栏差异。

## 四、部署与遗留

- **部署**：走 141 惯例（build.sh 打 fat jar → scp → 备份 → 原子替换 → 重启 →
  health + 抽查）。未执行，等指令。
- **遗留（记录不阻塞）**：
  - WebUI 亮度值域 0-100 vs App 0-200（App 滑条 max=200）——语义分叉待定夺；
  - 乙档协议镜像键「同步到 App」文案标注未做（可选优化）；
  - e2e visual.spec ROUTES 仍用 /admin/* 旧路径（靠 redirect 兜底，slug 不变）；
  - 环境：Node 20 vs engines >=22 警告（非阻塞）。
