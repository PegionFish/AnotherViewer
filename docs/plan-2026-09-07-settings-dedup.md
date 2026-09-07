# 设置页设置项梳理与去重（2026-09-07）

范围：统一设置面板 `/settings` 全部 14 个子页（偏好 4 页 + 服务器 10 页，A5-1 合并后格局），
外加两个与设置项强相关的相邻表面：阅读器内快捷设置面板（`components/reader/ReaderSettings.vue`）
与独立路由 `/smb-backup`（SMB 备份屏）。逐页读完模板与脚本、并下钻后端核实消费方后，
把发现分为「已修的去重」「待定夺的重复/死项」「核实为非重复的易混项」三档。

## 一、全量清单（梳理结果）

### 偏好（preferencesApi，按用户持久化，防抖 PUT /preferences）

| 子页 | 分组 | 设置项 |
| --- | --- | --- |
| 通用 | 通用 | 外观（主题三段）、跟随系统主题、启动页、显示阅读进度（卡片） |
| 通用 | 浏览 | 显示上传者、显示发布时间、默认收藏槽、最近搜索条数、收藏槽名称 |
| 通用 | 布局 | 详情栏宽度、缩略图大小、历史记录数 |
| 通用 | 画廊 | 显示日文标题、显示画廊页数、显示标签翻译、显示评论、显示评分、显示 EH 事件、显示 EH 限额 |
| 阅读器 | 翻页 | 阅读方向、翻页模式、首页作为封面、页面缩放、起始位置、自动播放间隔（步进器） |
| 阅读器 | 显示 | 显示进度、显示页间隔、全屏阅读、亮度（滑条） |
| 阅读器 | 交互 | 背景颜色、点击区域、键盘翻页、翻页过渡、缩放步进、最大缩放 |
| 阅读器 | 双页 | 双页间距、拆分宽页 |
| 阅读器 | 性能 | 预加载页数 |
| 隐私 | 隐私 | 启用统计（仅此 1 项） |
| 传输 | 配置传输 | 导出设置（偏好 JSON）、导入设置 |

### 服务器（settingsApi / backupApi / syncApi / privacyApi 等，服务器级）

| 子页 | 分组 | 设置项 |
| --- | --- | --- |
| 下载 | 基础设置 | 下载路径、并发线程数（workerCount）、下载延迟、下载超时 |
| 下载 | 并发限制 | 最大并发画廊数、最大并发图片数、预加载图片数（仅本设备） |
| 下载 | 列表与行为 | 排序模式（仅本设备）、每页条数（仅本设备）、自动开始下载（仅本设备） |
| 下载 | 维护 | 清理冗余文件、清理无效下载（两段式预览确认） |
| 筛选槽位 | — | 槽位列表、添加槽位 |
| 服务器 | 缓存 | 缓存路径、缓存大小、缓存统计、清除缓存（Job） |
| 服务器 | SMB 备份 | SMB 备份开关、前往备份页面（→ /smb-backup） |
| 备份 | 导出 | 包含下载内容、导出备份（Job） |
| 备份 | 还原 | 选择备份文件、输入确认词 RESTORE、执行还原（Job） |
| 备份 | 导入 EhViewer | 选择 .db、选择 Cookie 文件（可选）、执行导入（Job + 计数面板） |
| 设备 | — | 生成配对码、已配对设备列表 |
| EH 会话 | 站点 | 站点（e-hentai / exhentai 分段） |
| EH 会话 | 登录/Cookie | Android 登录引导 + 刷新状态；已登录时身份 Cookie 列表 + 登出 |
| EH 会话 | 代理 | 启用代理、代理类型、服务器地址、认证（用户名/密码）、测试/保存 |
| EH 会话 | E-Hentai | 外链×3（uconfig / mytags / 首页） |
| 访问 | 登录/密码 | 需要登录、Session 超时、修改密码 |
| 图像处理 | — | 启用图像处理、默认处理类型、输出格式、输出质量 |
| 高级 | 通用 | 界面语言、保存解析错误日志（均 localStorage） |
| 高级 | 隐私 | 内容打码模式（本地展示层 + privacyApi 服务端权威） |
| 高级 | 同步策略 | 冲突仲裁策略、自动同步间隔 |
| 高级 | 数据 | 导出数据、导入数据、清除本地数据 |
| 关于 | — | 版本、许可证、技术栈等只读信息 |

## 二、已修的去重（本次提交）

### D1 启动页下拉重复选项（通用页）

`LAUNCH_PAGE_OPTIONS` 原列 9 项以兼容旧值（`home`/`homepage` 同名「首页」、
`hot`/`whats_hot` 同名「热门」），AppSelect 原样渲染 → 下拉出现两个「首页」、
两个「热门」。改为只列 WebUI 真实存在的 5 个主路由
（首页/搜索/收藏/历史/下载），旧值经 `normalizeLaunchPage` 仅在显示层归一
（homepage/subscription/hot/whats_hot → home），不回写存储值；
用户一旦改选即落规范值。UX-03 的「存储值必须可见标签」语义由归一保证，
默认态触发器文案不变（首页），视觉基线不受影响。

### D2 起始位置下拉重复选项（阅读器页）

同类问题：`top-right` 与后端默认 `top_right` 同名「右上」并列两行。
改为 4 个 kebab-case 规范项，`top_right` 经 `normalizeStartPosition` 显示层
归一（不回写），`START_POSITION_LABELS` 同步去掉旧键。

### D3 阅读器快捷面板与设置页文案漂移

同一取值两个名称：快捷面板「从左到右/从右到左/竖向」vs 设置页
「左到右/右到左/纵向」；亮度 0 值一处「跟随系统」一处「系统」。
统一为设置页措辞（左到右/右到左/纵向/系统）。快捷面板与设置页共享
preferencesStore 持久化（ReaderView 经 `updateReader` 防抖落库），
值本身同源，仅文案不一致。

测试：GeneralSettings.spec / ReaderSettings.spec 的选项数断言
（9→5、5→4）与 UX-03 断言改为「归一后 modelValue + 标签可见」。
全量 vitest 1077 通过，vue-tsc 干净。

## 三、待定夺的重复/死项（记录不改，等实施指令）

### F1 高级 > 数据「导出数据/导入数据」= 备份页的子集（真重复）

两处调的是同一组 API（`backupApi.exportBackup` / `restoreBackup`）：
高级页固定元数据导出（`exportBackup(false)`），备份页多一个
「包含下载内容」开关；还原完全同端点、同 50MB 上限、同 R4-2 待重启横幅。
副作用是**破坏性操作护栏不一致**：备份页要求输入确认词 RESTORE，
高级页只 `window.confirm`。建议：删除高级页数据组的导出/导入两行，
保留「清除本地数据」，或整体降级为跳转备份页的链接行。

### F2 服务器页「SMB 备份」开关是死控件（真重复中的假开关）

后端存在两个都叫「SMB 启用」的状态：
- `config.smb.enabled`（SiteCoreConfigProperties 内存字段）——服务器页开关
  经 `PUT /settings {smb.enabled}` 写的就是它；引擎零消费，
  且 `SettingsService.updateSettings` 的 smb 分支只改内存、不像其余字段
  那样 `serverConfig.set` 落盘（重启即失，疑似后端遗漏）；
- `SmbConfigEntity.enabled`（`PUT /smb/config`）——SmbBackupView 的
  「启用 SMB 备份」开关写的是它，`SmbBackupService` 同步引擎只认这个。

即服务器页的开关既不控制真实行为、又不持久化，还可能与 /smb-backup 页的
真实状态相矛盾。建议：服务器页去掉开关，改为从 `GET /smb/config` 读真实
状态的只读状态行 + 保留「前往备份页面」链接；后端 smb 分支补 serverConfig
落盘与否一并定夺（若该开关删除则无需补）。

### F3 下载页「并发线程数」（workerCount）是死旋钮

全库检索：`workerCount` 只在设置 API 层往返（DTO ↔ SiteCoreConfigProperties），
下载引擎（`DownloadService` 的 pageExecutor 用 `maxConcurrentImages`，
画廊级并发用 `maxConcurrentImages`/`maxConcurrentGalleries`）零消费。
它是「并发限制」组两个旋钮之外的第三个无效果旋钮。建议：删除该行；
若未来引擎要区分线程池再回补。若删，SettingsDto/SettingsService 同步清理。

### F4 高级页两个 localStorage 半成品

「界面语言」自注释承认「尚未接入 i18n，仅记录偏好」，选项纯安慰剂；
「保存解析错误日志」写 localStorage 后无任何消费方。项目威胁模型是
私有单用户、无 i18N 规划。建议：两行都删（含 `AdvancedUi` 存储键读写），
或至少删「界面语言」。

### F5 放位：内容打码模式在「高级 > 隐私」，隐私偏好页却近乎空页

`/settings/privacy` 只有「启用统计」一行，而隐私属性更强的「内容打码模式」
（防风控定案功能，服务端权威持久化）在服务器分组的高级页里。打码是
用户级显示偏好（跟随所有客户端生效），放偏好侧「隐私」页更合身；
高级页则保持同步策略等服务器议题。挪移涉及 AdminAdvanced/PrivacySettings
与其测试，等定夺。

### F6 命名：「服务器 > 服务器」三层同名

分组标签「服务器」、路径 `/settings/server/server`、页题「服务器」，
页内实际内容是缓存 + SMB 入口。建议页题改「缓存与存储」或「存储」，
settingsSections 标签同步，路径不动（避免破坏深链）。

## 四、核实为「非重复」的易混项（不动，留档防误伤）

- **传输页「导出/导入设置」vs 备份页「导出/还原」**：前者是按用户偏好 JSON
  （preferencesApi，迁移 WebUI 偏好），后者是服务器库+配置 zip
  （backupApi，全量备份）。数据域与 API 都不同，不是重复；但三者都叫
  「导出」，若做 F1 建议顺手把高级页残留文案让位，避免三「导出」并立。
- **阅读器快捷面板 vs 阅读器设置页**：方向/翻页模式/自动播放/亮度 4 项
  重叠，但同源持久化（preferencesStore），属 by design 的就近调节表面，
  已做 D3 文案统一，无需合并。
- **阅读器「预加载页数」vs 下载页「预加载图片数」**：前者 reader.preloadCount
  （阅读器翻页预取），后者下载列表设备本地预取，域不同。
- **通用页「显示阅读进度」vs 阅读器页「显示进度」**：画廊卡片进度标记
  vs 阅读器内进度显示，域不同。
- **通用页「默认收藏槽/收藏槽名称」vs 服务器「筛选槽位」**：收藏槽位
  （ favorites，按用户）vs 站点筛选槽位（服务器级），域不同。
- **下载页「并发线程数」vs「最大并发图片数」**：语义确有重叠，但前者
  死旋钮（F3 删），后者是真实引擎参数；删除后重叠自然消失。

## 五、本次改动文件

- `web-frontend/src/views/settings/GeneralSettings.vue`：启动页 5 项 + 显示层归一（D1）
- `web-frontend/src/views/settings/ReaderSettings.vue`：起始位置 4 项 + 显示层归一（D2）
- `web-frontend/src/components/reader/ReaderSettings.vue`：方向/亮度文案统一（D3）
- `web-frontend/src/views/__tests__/GeneralSettings.spec.ts`、`ReaderSettings.spec.ts`：断言更新
- 验证：vitest 全量 1077 通过；vue-tsc 无错；视觉基线默认态文案不变

## 六、可用性审查（同日第二轮：逐键「写入→存储→消费」全链路核对）

方法：前端 18 个 general 键 + 19 个 reader 键 + enableAnalytics 逐键查 Web 消费方；
后端 settings 逐字段查落盘与引擎消费；App 端抽查 10 键区分「协议镜像真消费」与
「仅同步清单」。所有 DEAD 判定经人工复核（非仅 grep 一次下结论）。

### 6.1 偏好侧（2026-09-07 勘误后定稿）

> **勘误**：本节初版曾据「App 代码 grep 键名零命中」判定一批键「双端皆死」，
> 两处方法错误：一是 PreferenceSyncHelper 做的是 **App 原生设置键 ↔ JSON 键**
> 映射（如 `launchPage` ↔ `launch_page`、`autoPlayIntervalSec` ↔
> `start_transfer_time`），JSON 键名零命中≠功能不存在；二是抽查漏了 Kotlin
> 源（getShowJpnTitle/getShowEhLimits 的消费全在 .kt 里）。经逐键追原生键
> 的 getter 调用方核实，**用户框架成立：除图像处理（已知未完成）外，
> 偏好键全部对应 Android 端真实功能**。以下为修正后分级。

**分级修正后的事实基线**

- **同步协议镜像键（App 真消费，已逐键核实消费方）**：
  `launchPage`（MainActivity/SolidScene）、`showJpnTitle`（SiteUtils/FavoritesScene）、
  `showGalleryPages`（GalleryAdapterNew/SiteEngine）、`showTagTranslations`
  （SearchBar/UserTag/GalleryDetailScene）、`showGalleryComment`/`showGalleryRating`
  （GalleryDetailScene 等）、`showEhEvents`（SiteApplication）、`showEhLimits`
  （LimitsCountView）、`enableAnalytics`（PrivacyFragment）、`thumbSize`/`detailSize`/
  `historyInfoSize`（SiteFragment）、`startPosition`（GalleryActivity/PagerLayoutManager）、
  `firstPageCover`/`autoPlayIntervalSec`/`showProgress`/`showPageInterval`/`fullscreen`
  （GalleryActivity）、`theme`/`themeAutoSwitch`（Settings 原生键）、`readingDirection`/
  `pageMode`/`pageScaling`/`brightness`（App+Web 双端）。
  **含义：WebUI 设置行不是死设置，是双向同步的 App 设置遥控器**——Web 端改动
  会经同步真实改变 App 行为。
- **Web 端也已消费（双端生效）**：`showReadProgress`、`recentSearchMax`、
  `favoriteSlotNames`、`showUploader`、`showPostedTime`、阅读器
  `backgroundColor`/`tapZoneScheme`/`keyboardPaging`/`pageTransition`/`preloadCount`。
- **Web 本地新增、不在同步协议（App 不认识）**：Wave-1 自加的 `zoomStep`/
  `maxZoom`/`splitWidePages`（两端零消费）与 `dualPageGap`（半接线：写
  `--reader-dual-gap` 但全库 `var()` 零引用）——这是仅存的真·接线欠账。

**遗留问题只剩三类（都是 Web 侧欠账，不是废键）**

1. Web 有对应功能但不读偏好（App 生效、Web 无视）：详情页无条件渲染的
   `showGalleryPages`/`showGalleryComment`/`showGalleryRating`；`showJpnTitle`
   （Web 由打码开关 gate、不读偏好，语义与 App 不一致）；阅读器
   `firstPageCover`/`startPosition`/`autoPlayIntervalSec`/`showProgress`/
   `showPageInterval`/`fullscreen`（Web 阅读器未接线，其中
   `autoPlayIntervalSec` 与快捷面板 chips/播放器常量三方脱节）。
2. `theme` 双源分叉（toggleTheme 只写 localStorage、服务器值不回灌）与
   `themeAutoSwitch` 闸门脱节（theme.ts 跟随系统的条件是「localStorage 无值」，
   与开关无关）。
3. `zoomStep` 语义错位（设置页乘法 vs 阅读器加法 0.25）与 `maxZoom` 默认值
   5 > READER_ZOOM_MAX=3 不可达——接线前须先定语义。

### 6.2 偏好侧其他逻辑问题

1. **theme 双源分叉**：themeStore 只认 localStorage（theme.ts:23-33），服务器
   `general.theme` 从不回灌；抽屉/顶栏 toggleTheme 只写 localStorage 不写 prefs
   → 多端视角静默分叉。设置页 setTheme 双写是唯一同步点。
2. **themeAutoSwitch 与现有系统跟随打架**：theme.ts 的跟随系统闸门是
   「localStorage 无值」，用户选过一次主题即永久隐式关闭，设置页开关对此无作用
   （该键本身也是甲档死键）。
3. **prefs 加载时序坑**：HistoryView/DownloadView 深链直入时只读不 `load()`
   → `showReadProgress` 角标按防御默认隐藏，直到访问过会 load 的页面。
4. **defaultFavoriteSlot 注释级谎言**：CardQuickActions/favorite.ts 注释声称收藏带
   slot，实际唯一调用 `GalleryDetailView:464` 是 `addFavorite(gid, token)` 不传 slot。
   （该键在甲档/乙档之间：App 端消费情况未核实。）
5. **后端校验偏松（PreferenceDto）**：`historyInfoSize`/`autoPlayIntervalSec`/`brightness`
   完全无注解；`zoomStep`/`maxZoom`/`dualPageGap`/`preloadCount`/`recentSearchMax` 缺上限
   （其他客户端可写任意大值；Web 端 `preloadCount` 消费侧也只做 `?? 2` 兜底无 clamp）。

### 6.3 服务器侧

**落盘机制**：SQLite `server_config` 键值表；`SiteCoreConfigProperties` 修改**不会**
自动落盘，必须显式 `serverConfig.set`；启动回喂只有 SiteDataDirInitializer——
仅恢复 download.path 和 cache.path 两个路径。

| 字段组 | 落盘 | 引擎消费 | 结论 |
| --- | --- | --- | --- |
| download.path | ✅ | ✅ | 生效 |
| workerCount | ❌ | ❌（全仓零消费；core 的 SiteCoreConfig.java 整类死代码） | F3 死旋钮 |
| downloadDelay | ❌ | ✅（每次请求读） | **运行时生效但重启即失** |
| downloadTimeout | ❌ | ✅（OkHttp timeout） | 同上 |
| maxConcurrentGalleries | ❌ | ⚠️ **仅 Bean 构造时读一次**建固定线程池（DownloadService.kt:71-76），运行时改无效 | **任何时刻都不可生效**，比死旋钮更隐蔽 |
| maxConcurrentImages | ❌ | ✅（每次任务构造时读） | 新任务生效，重启即失 |
| cache.path | ✅ | ✅（旧缓存不迁移，仅新写入落新目录） | 生效 |
| cache.sizeMb | ❌ | ✅（LRU 淘汰阈值） | 运行时生效，重启即失 |
| smb.enabled | ❌ | ❌ | F2 死开关（后端 smb 分支亦不落盘） |
| security.requireAuth | ✅ | ✅（HTTP/WS 全量门禁） | 生效；⚠️ env `ANOTHERVIEWER_REQUIRE_AUTH` 存在时 DB 值被无视 |
| security.sessionTimeout | ✅ | ✅（token TTL，消费端下限 60s） | 生效 |
| processing.*（4 项） | ✅ | ❌ **整组零消费**——管线只有 NoopProcessor 占位（恒不可用），ProcessingController 不注入配置，enabled/defaultType/outputFormat/outputQuality 只回显 | 「存了没人看」整组 |
| proxy.* | ✅ | ✅（每次调用实时读 DB，改即生效） | 生效 |
| sync.conflictStrategy | ✅ | ✅（SyncService push→merge* 全分支真实消费） | 生效 |
| sync.autoSyncIntervalSec / clientTier | ✅ | ⚠️ 服务端仅存储转发（App 客户端消费） | 符合契约，非缺陷 |

**新增待定夺项（接 F 系列编号）**：
- **F7** 下载页数值组持久化缺口：workerCount 之外的 delay/timeout/maxConcurrentGalleries/
  maxConcurrentImages/cache.sizeMb 共 5 项只写内存重启即失（后端补 serverConfig.set
  + SiteDataDirInitializer 回喂，或前端明示「重启失效」）。
- **F8** maxConcurrentGalleries 特殊：即使落盘，线程池构造期固化也使其无法运行时生效
  （需改 setCorePoolSize 或任务级读取）。
- **F9** 图像处理整组占位：设置页 4 项 + 维护操作均无真实管线（NoopProcessor 恒不可用）。
  处置：设置页隐藏整组/标注「未实现」，或明确立项接真实处理器。
- **F10** preference 校验收紧：PreferenceDto 补上限/范围（与前端 clamp 对齐），
  防 Web 之外的其他客户端写脏值。

### 6.4 处置建议总纲（勘误后，等指令，本轮未改任何代码）

- **协议镜像键一律保留**：它们对 App 真实生效，删除会伤及 App 设置面。
  可选优化：WebUI 行文案标注「同步到 Android App」，管理预期。
- **Web 接线欠账（若立项，建议顺序）**：
  1. 详情页三开关（showGalleryPages/Comment/Rating）+ showJpnTitle——Web 有
     对应 UI，接线成本最低（v-if 挂偏好即可；showJpnTitle 需定「与打码
     开关的叠加语义」）；
  2. 阅读器六键（firstPageCover/startPosition/autoPlayIntervalSec/showProgress/
     showPageInterval/fullscreen）——涉及阅读器逻辑，接线时顺手消除
     autoPlayIntervalSec 三方脱节（偏好 ↔ 快捷面板 chips ↔ 播放器常量）；
  3. zoomStep/maxZoom/dualPageGap/splitWidePages——先定语义（zoomStep 乘法
     vs 加法；maxZoom 与 READER_ZOOM_MAX=3 对齐）再接线，dualPageGap 补
     `var(--reader-dual-gap)` 消费；
  4. theme 双源修复：toggleTheme 同步写 prefs、preferences 加载后回灌
     themeStore、themeAutoSwitch 接管 theme.ts 的系统跟随闸门。
- **F9 图像处理**：确认为已知未完成功能（占位 NoopProcessor），维持记录，
  不再作为发现项。
- **F7/F8（下载数值组落盘 + maxConcurrentGalleries 构造期固化）**：后端小改动
  高收益，建议优先——这是唯一直接损害现有已生效设置的缺口。
- **F10（PreferenceDto 校验收紧）**：与各键接线一起做即可。
- **F1-F6（第一轮去重/死项/放位）**：维持原建议不变；F4 的「界面语言/
  保存解析错误日志」两键不在同步协议里、两端皆无功能，删除建议成立。

> **实施记录（2026-09-07）**：F1-F10 已全部落地，多代理并行实施，
> 前端 1105 / 后端 977 测试全绿，基线 229 屏重拍通过。
> 实施细节与默认决策表见 [exec-log-2026-09-07-settings.md](exec-log-2026-09-07-settings.md)。

