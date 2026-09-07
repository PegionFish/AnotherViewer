# 诊断与修复方案：首页语义 / 下载分页 / 阅读进度 / 布局优先级 / 设置合并 / 运行日志（2026-09-06）

状态：**仅诊断与方案，未动代码、未上实例验证**（按用户指示，等明确指令再实施）。
评估对象：`http://192.168.6.141:8081/` 线上实例（代码 ≈ HEAD `a891f520`，下载分页丢失可复现说明前端已是 098f1c04 之后版本）。全程未用 curl 访问任何内容/文件接口，仅静态代码链路核对 + 本地 vue-router 行为实验。

## TL;DR

| # | 症状 | 根因 | 定性 |
|---|------|------|------|
| 1 | 首页混杂历史记录 | `GalleryService.searchGallery` 空关键词时**本地历史优先**，历史非空直接 return，站点最新列表只是兜底 | 设计偏离 Android 基准，需翻转优先级 |
| 2 | 下载页分页又丢失 | `098f1c04`（2026-09-05）**主动退役**了分页条/条数切换/滚动追加，改一次拉全量（后端 limit 500→100_000） | 昨日的全量化改造误判，需恢复分页为默认 |
| 3 | 重开内容从第 1 页开始 | vue-router 4 可选参数缺省时 `route.params.page === ''`，`Number('') === 0` 有限 → `resolveStartPage` 把无页参进入当成「显式深链第 0 页」，**永远不读 `detail.readProgress`**；单测 mock 用 key 缺失形态，与真实路由不符所以全绿 | 前端一行级 bug + mock 保真度缺口；另有一个次级同步缺陷（见 §3.2） |
| 4 | 列表展示模式定案 | 三轮反馈收敛：瀑布流对本使用场景无意义（标题等元数据优先），**彻底出局**；所有列表**完全参照下载页面的逻辑** | §4 定案；A4 升级为在案工作（等实施指令） |
| 5 | 设置与管理面板割裂 | `/settings`（4 页，preferencesApi 用户偏好）与 `/admin`（10 页，settingsApi/backupApi 等服务器配置）双面板双入口；单一账号、同一路由守卫，**无任何权限分界**——拆分只是后端 API 分组的 UI 镜像。窄窗口下层级切换还是顶部横排标签条（<960px 折叠形态） | §5 定案：A5 合并为统一设置面板 + **Android 式层级导航**（窄屏进入/返回，顶部标签条退役）（等实施指令） |
| 6 | 运行日志不支撑性能分析 | 应用日志行已带 ISO 时间戳（Spring Boot 默认），但**无请求级耗时记录**——未启用 Tomcat access log，无任何 duration filter；部署机日志进 journald（持久化依赖 /var/log/journal 存在），dev 走 start.sh 体积轮转 | §6 定案：A6 启用 access log（时间戳+耗时 ms，落 data-dir/logs，按天滚动保留），零 Java 代码 |

---

## 1. 首页内容来源混乱（首页 ≠ 站点首页，混入历史）

### 诊断

`GalleryService.kt:128-132`：

```kotlin
if (keyword.isNullOrBlank()) {
    // 空 keyword：先给本地历史（浏览回看），历史为空则回退站点最新列表
    val local = searchLocalHistory(keyword, category, page, pageSize)
    if (local.data.isNotEmpty()) return local
    // ……历史为空才去站点拉最新列表（EH DOWN 则 success=false）
}
```

即：WebUI 首页 = 「本地历史（按时间倒序分页）优先，站点最新列表兜底」。只要历史表非空（同步过 App 数据的用户必然非空），首页**永远**是历史列表——与 Android 首页（站点根路径最新画廊）语义相反。代码注释里作者明知这一点（「Android 首页 = 站点根路径最新画廊」）却选择了历史优先。

用户观感：「首页默认展示的并非实际首页内容，混杂历史记录」——正是此逻辑的直接产物。历史本该由 HistoryView 承载（该页工作正常）。

测试锚点：`GalleryFeedServiceTest.kt:~340-359` 锁定了「EH 不可达 + 空关键词 → 返回本地历史（title="Local"）」的现状（该用例场景是 EH DOWN，翻转后语义仍成立，需微调断言场景）。

### 方案（A1：翻转优先级，对齐 Android）

1. `searchGallery` 空关键词分支改为：**站点最新列表优先**（现有 `buildSearchUrl("")` + `searchCache` 2min 缓存复用，流量可控）；站点成功 → 返回站点结果。
2. 站点不可达（`availability.isBlocked()` 短路或请求失败）→ 回退 `searchLocalHistory`，维持 E2E-6「只读本地内容仍可用」语义——与首页 `AvailabilityBanner`（"EH 平台当前不可达，仅显示本地内容"）文案天然配套。
3. 历史浏览回看诉求由 HistoryView 承载，首页不再承担。
4. 测试：新增「空关键词 + EH 可达 → 走站点最新列表（verify SiteEngine 调用）」；改造现有「EH 不可达 → 本地历史兜底」用例保留；`GalleryControllerTest` 的参数透传用例不受影响。

改动面：`GalleryService.kt` 单文件 + 2~3 个测试。前端零改动（HomeView 对 `total/data` 信封无感）。

**备选（不推荐）**：保留现状，前端加「首页/历史」切换 tab——多余的状态面，违背「简单优先」决策风格。

---

## 2. 下载页分页再次丢失

### 诊断（「又」字的来历）

DownloadView 分页是逐 commit 建起来的：`7d3f39b9`（服务端分页 + 每页条数，Android 50 条/页对齐）→ `df382ee7`（跨页全选 all 模式 + 正则全集）→ `63d20db5`（PC 分页条：直点页码窗口 + PageUp/Down）→ `aff288d8`（分页条初版）。

然后 `098f1c04`（2026-09-05，昨天）一次拆掉：

> feat(web): 下载列表一次拉全量——后端 limit 上限 500→100_000，前端跳页分页/条数切换/滚动追加退役（虚拟滚动渲染不变），批量全选退化为显式 ids

动机（从 diff 推断）：让批量全选在客户端持有全量 ids，简化跨页选择。代价：**首页请求即拉全量下载行**（该库导入过 8797+ 行的 .db），无谓的首屏负载与内存；跳页/条数控制这些已建好的能力全部消失。这是误判，不是回归丢失——所以 git 历史里分页代码还在，只是被删了。

### 方案（A2：恢复分页为默认，保留有价值的部分）

1. **前端恢复** `098f1c04` 退役的分页 UI（以 `63d20db5`/`aff288d8` 形态为底）：分页条（直点页码窗口、前后页、跳页、PageUp/Down）、每页条数切换（默认 50，对齐 Android）；保留 `098f1c04` 之后新增的虚拟滚动渲染与竞态守卫（`5daf1696` 等响应式修复不动）。
2. **后端恢复** limit 上限 500（回滚 `DownloadService` 的 100_000），服务端分页 + `q`/regex 筛选维持。
3. **跨页批量全选**：恢复 `df382ee7` 的服务端 all 模式（按筛选条件全集操作），替代「显式 ids」退化形态——这正是当初建 all 模式的原因。
4. 测试：恢复 `098f1c04` 删掉的 DownloadView 分页用例（其 spec 被删了 263 行），按新组件形态重写；`DownloadServiceTest` 恢复 limit 钳制用例。

**取舍说明**：一次拉全量的「省请求」收益在个人自用规模下不成立，而分页是 Android 基准交互且已两次建成——恢复成本低于再争论。

---

## 3. 阅读进度无法记录（重开从第 1 页开始）

### 3.1 主根因：`resolveStartPage` 被 vue-router 可选参数空串劫持

证据链（全部静态核实 + 本地实验定论）：

- 路由：`router/index.ts:21` → `/reader/:gid/:page?`。
- 真实路由行为（本地 node + 项目内 vue-router 4.5 实测）：
  - 进入 `/reader/123?token=abc`（**不带页段**，详情页 `read()` 与下载页 `buildReaderRoute` 都是这个形态）→ `route.params.page === ''`（空字符串，**不是** undefined）。
  - `Number('') === 0` 且 `Number.isFinite(0) === true`。
- `ReaderView.vue:549-570`：

```ts
function resolveStartPage(rawDeepLink, serverProgress) {
  const linked = Number(rawDeepLink)          // Number('') === 0
  if (Number.isFinite(linked)) return Math.max(0, Math.floor(linked))  // ← 无条件命中，返回 0
  if (serverProgress > 0) return Math.floor(serverProgress)  // ← 永远走不到
  ...
}
```

**所有不带显式页参的进入都被当成「深链到第 0 页」**，`detail.readProgress` 与 localStorage 兜底全部短路。写入侧（入场写 + 10 页/30s 节流写 + pagehide/unmount flush，`ReaderView.vue:589-632`）与服务端落库（`GalleryService.addToHistory` → `HistoryService.kt:80-105`，`page` 非 null 即写、打码只置空 title 不影响 page）**都是好的**——DB 里有真实进度，只是重开时不读。

为什么单测全绿：`ReaderView.spec.ts` 的路由 mock 是普通对象，用 `delete routeParams.page`（key 缺失 → `Number(undefined)` = NaN → 跳过深链分支 → 恢复分支生效）。真实 vue-router 给的是 `''`。**mock 保真度缺口**。

同类隐患：`ReaderView.vue:772-780` 的 `watch(route.params.page)` 守卫同样 `Number(page)` 直转——页参从 `'30'` 变 `''`（如路由被 replace 回无页参形态）会被误判为「跳到第 0 页」。

### 3.2 次级缺陷：Web 写的进度不向 App 传播（lastModified 不 bump）

- App 增量拉取走 `findByUsernameAndLastModifiedGreaterThan`（`SyncService.kt:120`）。
- Web 回写 `HistoryService.addHistory` 更新路径（`HistoryService.kt:84-87`）只改 `time/mode/page`，**不 bump `lastModified`**（实体无 JPA 审计注解，纯普通字段）。
- 结果：Web 阅读进度 App 永远拉不到（App→Server 方向有 `applyHistoryFields` 的 `page = max(…)` 保护，`SyncService.kt:901-904`，不受影响）。

这不影响「Web 自身重开」症状（那由 3.1 解释），但修 3.1 后此缺陷会立刻显形为「Web 读了 App 不同步」，应一并修。

### 3.3 已排除的嫌疑（核对记录）

- 打码过滤器（`PrivacyMaskFilter.redactObject`）只清 title/titleJpn/uploader/tags/galleryUrl/path——**不动 token 与 readProgress**，历史回写也只对 INSERT 的 title 置空、page 照写。
- download/favorite/history 三个 detail 分支都带 `readProgress = readProgressOf(gid)`（`GalleryService.kt:465/498/558`）。
- SW 对 `/api/*` 是 NetworkFirst（在线时新鲜响应），不会用陈旧 readProgress 糊弄重开。
- 详情页 `read()`、下载页 `buildReaderRoute` 均不带页参（这本来就是对的——问题在阅读器把「无页参」误读成「第 0 页」）。

### 3.4 方案（A3）

1. `resolveStartPage`：空串/非有限统一按「无深链」处理——
   `const raw = typeof rawDeepLink === 'string' ? rawDeepLink.trim() : ''`；`if (raw !== '' && Number.isFinite(Number(raw)))` 才走深链分支。
2. `watch(route.params.page)` 守卫同改（`''` → 忽略）。
3. 测试：mock 改为镜像真实路由（`routeParams.page = ''` 而非 delete），并新增回归用例「无页参 + readProgress=12 → 起始页 12」；顺带把深链用例（page:'30'）保留。
4. 服务端：`HistoryService.addHistory` 更新路径补 `existing.lastModified = System.currentTimeMillis()`（进度变化即 bump，App 增量拉取立即可见；LWW/take-max 语义不受影响——incoming 推送在 skew 内按 `time` 比较，Web 写的 `time` 是最新）。
5. 验证顺序（待指令后）：单测 → 本地 dev 复现（进阅读器翻 10+ 页 → 退出 → 重开应回原页）→ 线上实例。

---

## 4. 展示模式定案（2026-09-06 三轮反馈收敛，设计约束，无代码改动）

### 4.1 反馈演化

1. 「使用瀑布式图片流可以作为一个选项，但默认情况下下载页面的布局形式才是最高优先级。」
2. 「现在只有下载页面的展示模式符合我的预期。」
3. 「以我们的使用场景而言，瀑布流是没有意义的，标题什么的都更重要。可以完全不考虑瀑布流，完全参照下载页面的逻辑进行列表。」

收敛结论：**瀑布流彻底出局**（连可选档都不做）；**所有列表视图完全参照下载页面的逻辑**。理由在使用场景本身：浏览决策靠标题/标签等元数据，缩略图只是辅助——封面墙与瀑布流都是图片优先形态，信息密度天然不匹配。

### 4.2 现状盘点（各视图展示模式）

| 视图 | 现行形态 | 默认 | 符合预期？ |
|------|---------|------|-----------|
| 下载 DownloadView | 全宽**单列**密信息行（缩略图+标题+进度/状态+速率），虚拟滚动，行内点击分区（缩略图→详情/主体→阅读），分页待 A2 恢复 | 单列列表（无切换档） | **✅ 基准** |
| 首页 HomeView | GalleryList：grid 封面墙 / list 档切换（`prefs.general.listMode`） | grid 封面墙 | ❌ |
| 搜索 SearchView | 自带 localStorage `search-view-mode` + GalleryCard 直渲染 | grid 封面墙 | ❌ |
| 历史 HistoryView | GalleryList（同首页） | grid 封面墙 | ❌ |
| 收藏 FavoriteView | GalleryList（同首页） | grid 封面墙 | ❌ |

**关键事实**：GalleryList 的 list 档在 PC 上是 `--column-width-list-long`（480px）auto-column 的**横卡多列卡墙**（宽屏 4 列），并非下载页那种全宽单列密行——即下载页形态在其余视图的**现有选项里根本不存在**，切 list 档也得不到。

### 4.3 定案

1. **瀑布流**：彻底出局——不设计、不实现，不作为任何视图的可选项，永久搁置。
2. **基准**：下载页列表逻辑 = 全站列表唯一形态：全宽单列密信息行、虚拟滚动、分页导航、元数据优先（标题/标签 > 缩略图）；A2 恢复分页是该基准的收尾件。
3. **其余列表视图**（首页/搜索/历史/收藏）：**A4 从「可选方向」升级为「已定案的在案工作」**——四视图整体切换到下载页列表逻辑；grid 封面墙与横卡卡墙档废弃（下载页本就无模式切换，"完全参照"即单一形态、零切换）。实施指令下达前不动代码。
4. Android 基准一致性：Android 下载 Scene 本就是单列列表，A4 是让其余列表向已认可形态看齐，不是另起炉灶。

### 4.4 A4 实施要点备忘（等指令后细化）

- **共享行组件**：从 DownloadView 抽出通用单列行（缩略图 + 标题/日文标题 + 分类/标签 + 页数 + posted），视图差异用属性/槽位表达——下载行多进度/状态/速率，历史/收藏行多进度角标。
- **模式体系收编**：GalleryList 的 grid/list 双档与 `prefs.general.listMode`、SearchView 的 localStorage `search-view-mode` 一并退役；HomeView 自研虚拟窗口的行高/列数数学按单列重算（DownloadView 的 tanstack virtualizer 方案可直接复用）。
- **分页**：历史接口已支持 page/pageSize；收藏接口现返回全量（是否补服务端分页实施时定）；首页/搜索是上游 25 条/页——分页条是否同样替换无限滚动，实施时定。
- **点击分区**：下载行是「缩略图→详情 / 主体→阅读」；列表场景是否沿用同分区（Android 列表场景是整卡→详情），实施时定。

---

## 5. 设置与管理面板合并（A5，2026-09-06 新增定案）

### 5.1 现状盘点

两个面板、两个抽屉入口（导航抽屉第 10 项「管理面板」带 web-only 分隔线）、两套路由树与布局组件：

| 面板 | 路由 | 页面 | 数据 API |
|------|------|------|---------|
| 设置 `/settings`（SettingsLayout） | general/reader/privacy/transfer | 通用、阅读器、隐私、传输（4 页） | `preferencesApi`——与 App 同步的用户偏好 |
| 管理 `/admin`（AdminLayout） | download/filter-slots/server/backup/devices/eh/access/processing/advanced/about | 下载、筛选槽位、服务器、备份、设备、EH 会话、访问、图像处理、高级、关于（10 页） | `settingsApi`/`backupApi`/`syncApi`/`privacyApi`/`jobsApi`/`authApi` 等——服务器配置与运维 |

**拆分无权限依据**：单一账号体系、同一路由守卫（token 或 authRequired=false 即全放行），admin 面板没有独立门控。拆分只是后端「用户偏好 vs 服务器设置」两组 API 在 UI 上的镜像。个人自用场景下，14 个页面分两处找是纯摩擦——用户判断「拆成两个没啥意义」成立。

### 5.2 定案（等实施指令）

1. **合并为统一设置面板**：单一 `/settings` 路由树 + 单一 Layout + 单一导航；抽屉去掉「管理面板」入口与分隔线，只留「设置」一项。
2. **分组导航**（Layout 内分区，避免 14 项平铺）：
   - **偏好**：通用、阅读器、隐私、传输（原样保留）；
   - **服务器**：下载、筛选槽位、服务器、备份、设备、EH 会话、访问、图像处理、高级、关于（原 /admin 各页平移）。
3. **层级导航范式（Android 逻辑，2026-09-06 补充定案）**：页内多级菜单的层级切换跟随 Android 端交互——
   - **宽视口**（沿用现有 ≥960px 断点）：双栏 two-pane，左侧分区列表 + 右侧内容（现状宽屏形态保留）；
   - **窄视口**（<960px）：**顶部横排标签条退役**。分区列表本身就是页面（master，按「偏好/服务器」分组的大行列表）；点击分区进入子页（detail），子页页头带返回箭头（up 语义），Android 返回手势/系统返回同链路。即窄屏的层级 = 路由级前进/后退，不是同页按钮切换；
   - **通用约束**：未来任何页内多级结构一律照此——受限尺寸下用进入/返回，不做页顶按钮排。
4. **路由兼容**：旧 `/admin/*` 路径全部 `redirect` 到新 `/settings/*` 对应路径，书签与肌肉记忆不断。
5. **后端零改动**：两组 API 原样保留，合并纯前端重组。

### 5.3 实施要点备忘

- AdminLayout 与 SettingsLayout 二合一（分组侧栏/页签组件保留哪个形态，实施时按现有 CSS 定）；页面组件本身零改动或仅改路由名。
- **窄屏层级导航落地**：<960px 分支删除横向标签条（含其汉堡避让与右缘渐隐 CSS）；新增 `/settings` 根列表视图（分组大行，复用抽屉行形态与 44px 触控行高）；子页页头返回箭头——`history.back()` 优先、无历史兜底 push 根列表（详情页 goBack 的 C5 同款模式）；触屏边缘返回手势若复用阅读器的 composable，实施时评估。
- `/smb-backup` 顶层独立路由（SMB 备份向导）是否顺带收进设置面板「备份」区，实施时定。
- 测试迁移：AdminLayout/Admin*.spec 的路由与标签断言更新（组件文件名可不动，避免无谓 diff）；补窄屏层级导航的响应式用例（live-responsive.mjs 矩阵加设置域截图）。
- 与 A4 的耦合：两者都动 NavigationDrawer 与视图层，实施时若并行注意文件域拆分（A4 动列表视图，A5 动设置域，天然不冲突）。

## 6. 运行日志与性能分析支撑（A6，2026-09-06 新增定案）

### 6.1 现状盘点

| 环节 | 现状 | 缺口 |
|------|------|------|
| 应用日志时间戳 | ✅ 已有——Spring Boot 默认 console 格式带 ISO-8601 毫秒级时间戳（anotherviewer-web.log 实样确认） | 无 |
| 请求级耗时 | ❌ 完全没有——未启用 `server.tomcat.accesslog`，config/ 下无任何 request-duration filter/interceptor | **性能分析的核心缺口** |
| 部署机落盘 | systemd → journald（`journalctl -u anotherviewer-web`），带时间戳有保留策略 | journald 持久化依赖 `/var/log/journal` 存在（默认 auto），未显式保证 |
| dev 落盘 | start.sh → 根目录 anotherviewer-web.log，已有体积轮转（.1/.2/.3，LOG_MAX_BYTES） | 无请求耗时，同上 |
| 运行时指标 | MetricsController 快照（cache/JVM/version） | 是即时快照，不是可回溯的历史日志 |

### 6.2 定案（等实施指令，纯配置、零 Java 代码）

1. **启用 Tomcat AccessLogValve**（application.yml）：

```yaml
server:
  tomcat:
    accesslog:
      enabled: true
      directory: ${anotherviewer.data-dir}/logs   # 部署机 = /opt/anotherviewer/data/logs
      prefix: access
      file-date-format: .yyyy-MM-dd
      suffix: .log
      max-days: 30
      pattern: '%t %a "%r" %s %B %D'
```

   - 每请求一行：`%t` 时间戳、`%a` 客户端 IP、`%r` 方法+路径+协议、`%s` 状态码、`%B` 响应字节、`%D` **耗时毫秒**——性能分析所需的最小完备集。
   - **路径约束（真实部署限制）**：systemd 单元 `ProtectSystem=strict` + `ReadWritePaths=/opt/anotherviewer/data`，全盘只读、仅 data 可写——日志目录必须落在 `${anotherviewer.data-dir}/logs`，不能放 /opt/anotherviewer 根或 /var/log。
   - 按天滚动、保留 30 天（个人自用足够，Tomcat 自动清理过期文件）。
2. **应用日志维持 journald 路径**，时间戳已具备；deployment.md 补一条 journald 持久化保证（`mkdir -p /var/log/journal` 或 unit `Storage=persistent`）。
3. **分析工作流入档**（deployment.md）：慢请求 Top N 一类直接对 access.log grep/awk（`$NF` 排序即可）；SPA 静态资源与 API 共用日志，分析时按 `/api/v1/` 前缀过滤。
4. **已知取舍**：`%r` 含 query string——搜索词、gid 会进本地日志。打码模式防的是站点风控，本地自用日志不受此约束，接受。

### 6.3 可选后续（不排期）

- 应用内打点（上游 EH 请求耗时、DB 慢查询）——access log 的 `%D` 覆盖端到端后，只在需要归因「慢在 EH 还是慢在本地」时才值得做。
- 前端 Performance API 上报（页面加载/资源耗时落服务端）——同样等实际分析需求出现再议。

## 7. 整体复审细化（2026-09-06 第二轮，全部仍为定案层面，未动代码）

本轮对 A1–A6 逐项压力测试 + 补事实核查（收藏接口、内部 /admin 引用、下载排序现状、KeepAlive 清单），产出以下细化与新决策。

### 7.1 逐项细化

**A1 首页翻转**
- 冷启动代价入档：翻转后首页首载（缓存 miss 时）要等一次 EH 往返（searchCache 2min）；Android 同语义同代价，前端 loading 态已有，接受。EH DOWN 路径语义不变（history 兜底 + 横幅）。
- 顺带修复：空关键词 + 高级筛选（FilterPanel）现行被 `searchLocalHistory` 吞掉——翻转后 `f_*` 参数直达站点，筛选在首页真正生效。
- 不删代码：`searchLocalHistory` 原样保留为 EH-DOWN 兜底，仅调换优先级——改动就是分支重排。

**A2 下载分页恢复**
- 事实核查：排序模式在 098f1c04 中幸存（SORT_MODE 与 AdminDownload 同键共享）——A2 只重建分页条/条数切换/all 模式，排序不碰。
- 简化机会（实施时定）：分页恢复后每页 ≤50–200 行，tanstack 虚拟滚动变得可有可无——倾向直接退役虚拟化（连带的滚动容器探测逻辑一起删），50–200 行原生渲染无压力，少一套活动部件；若保留 200 条/页档则保留虚拟化。
- 批量语义明确为混合式：当前页选择 = 显式 ids；跨页全选/批量 = 服务端 all 模式（df382ee7 形态，含 q/regex 全集）。
- 条数选项：默认 50（Android 契约），上限 200 对齐全局 MAX_PAGE_SIZE；098f1c04 新增的 100_000 上限测试用例改回 500。

**A3 阅读进度**
- 修复范围补全：`watch(route.params.page)` 的守卫与**它的测试**同属一类 mock 保真问题（`''` ≠ key 缺失），一并修。
- lastModified bump 的同步推演（入档防返工）：bump 后 App 增量拉取可见 Web 进度 ✓；App 后续 push 同行时 LWW 比 Web 新写的 lastModified 旧 → incoming 败、服务端保 Web 行 ✓，且 `page = max(...)` 在任何胜出路径都护住进度 ✓——不引入新冲突类别。
- 入场回写幂等：恢复到第 N 页后 entry write 回写 N，upsert 无害。

**A4 列表全站对齐下载页（新增两个已定决策）**
- **收藏后端补分页**（事实核查后定案）：`getLocalFavorites()` 现为全表扫描一次性返回——补 page/pageSize/total（对齐 history list 形态），否则收藏页放不下分页条。原「实施时定」提前定案：**做**。
- **首页/搜索用分页条替换无限滚动**（对齐下载页逻辑的直接推论）：上游 total = pages×25 可驱动页码；KeepAlive 原位还原语义从「滚动位置」变为「页码+页内滚动」（fullPath 分实例机制不变）。
- `prefs.general.listMode` 是 App 同步键：Web 侧**彻底停读停写**（避免同步扰动），不删 App 端语义；GalleryList/GridCard 的 grid/list 双档、模式切换工具条、HomeView 手搓虚拟窗口数学（列数/行高估算）全部随切换删除——A4 的净效果是删码大于增码。

**A5 设置合并**
- 事实核查：内部 `/admin` 引用共四处——NavigationDrawer 的 NAV_TARGET_PATHS、App.vue 路由→导航项映射（高亮用，非 KeepAlive；CACHED_VIEWS 只含五个列表视图）、HomeView:458 的 EH 会话跳转、路由表本体。redirect 只兜 URL 层，**导航高亮映射与内部跳转必须同步改**，否则高亮落空。
- 根路由形态定案：`/settings`（exact）窄屏渲染分组索引页，宽屏 setup 内一次性 matchMedia 检查后 `router.replace` 到默认子页——不做 CSS 双渲染的取巧；子页返回箭头仅窄屏可见（宽屏双栏常驻）。
- 测试：Admin*.spec 多数走真实 router，redirect 可兜路径断言，但标签/分组断言按合并后更新。

**A6 运行日志**
- journald 持久化方式定案：unit drop-in `Storage=persistent`（自包含，不依赖手工 mkdir /var/log/journal），deploy README 补一行。
- dev 一致性：accesslog 目录走 `${anotherviewer.data-dir}/logs`，dev 也是 data/logs——与部署机同构；start.sh 的体积轮转只管 console 日志，access log 由 max-days=30 自轮转，互不干扰。
- pattern 维持最小集（%t %a %r %s %B %D），不加线程/协议字段。

### 7.2 横向统筹

- **实施波次**（替代原线性顺序，文件域正交）：
  - **W1 = A3 + A1 + A6**：阅读器域 + 后端搜索域 + 纯配置，三者互不接触，可并行一轮完成；
  - **W2 = A2**：下载域（分页条 + all 模式 + 后端 limit 回滚）；
  - **W3 = A4**：列表视图域，依赖 W2 产出的行组件与分页条形态；
  - **A5 随时可并行**：设置域与上述全部正交。
- **视觉基线一次性刷新**：A2/A4/A5 都触视觉（1ed3ad20 先例）——基线刷新合并为 W3/A5 完成后的一次提交，不做三轮。
- **部署合并**：全部六项零 DB schema 变更（无 SQLite DDL 补列坑），前端 + jar 单次部署收口；A6 配置随同一 jar 生效，为收口后的验证清单走查提供耗时数据。
- **风险登记**：A1 冷启动延迟（接受）；A4 listMode 停读写防同步扰动；A5 导航映射遗漏（已列 grep 清单）；A2 虚拟化退役与否挂条数档位决定。

## 8. 代码审计联动（2026-09-06，详见 docs/audit-2026-09-06-webui.md）

全库四域审计（后端安全/数据并发、前端状态/网络、契约/测试）产出 P0×1、P1×6、P2 若干，全部经人工复核。与本案的直接联动：

- **A7（新立）= 审计 P0-1**：Web 本地写不落 username/lastModified/墓碑 → Web 加的收藏 App 永远看不到、Web 删除被 App 推送复活（`adoptNullOwnership` 一次性收养假设被违反）。**吸收原 A3 的 lastModified bump 子项**，A3 缩回纯前端修复（resolveStartPage 守卫 + watcher 守卫 + spec mock 保真）。stamping 目标已核实 = `authentication.name`（两种认证模式下与 App 推送天然一致）；同步路径 findByGid 的多用户爆炸（审计 P1-1，功能完整性缺陷）列入 A7 正式范围一并改带属主维度。
- 随波次搭车的审计小修：P1-4 useEnhancedImage 跨画廊污染、P1-5 SearchView 全局键停用劫持 → W1；P2 后端性能项（列表全表过滤、searchCache 无 maximumSize）→ 与 A2 同批；P2 前端项（preferences dirty 卡死、loadingMore 脆弱结构等）→ 与 A4 同批。
- 威胁模型定案（2026-09-06）：单人用户 + 私有网络 infra、不开放外网，安全类发现降级为「记录不改」（注册开关/metrics 裸奔/token 存储等，详见审计报告）；**内容打码不在放宽范围**。findByGidAndUsername 作为纯健壮性改造随 A7 顺带；契约补齐/死端点清理为低优先随手项。
- 波次更新：**W1 = A3(缩减版)+A1+A6+两个前端小修** → W2 = A2+后端性能小修 → W3 = A4+前端 P2 批 → A5 随时并行 → **A7 单独成文实施**（涉及 7 表写路径与认证上下文，面最大，建议在 W1-W3 落定后专门做）。

## 遗留验证清单（实例走查，待指令后执行）

- [ ] 打码开启下走通：首页（翻转后）站点最新列表、空关键词+筛选生效、下载分页、阅读进度重开恢复。
- [ ] App 同步：Web 读至 N 页后，App 增量拉取能拿到 N（验证 lastModified bump）。
- [ ] 下载页跨页全选 + 批量操作（all 模式恢复后）；排序模式不回归。
- [ ] A4 后各列表页分页条 + 单列行形态 + KeepAlive 页码还原。
- [ ] A5 后旧 /admin/* 深链可达（redirect）、导航高亮正确、窄屏层级进入/返回。
- [ ] live-responsive.mjs 矩阵截图过一遍（A2/A4/A5 完成后一次性刷新基线）。
- [ ] A6：部署后确认 `data-dir/logs/access.*.log` 出现且 `%D` 有值；慢请求 Top N 分析命令跑通。
