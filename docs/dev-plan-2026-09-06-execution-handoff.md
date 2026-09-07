# 交付执行计划：TODO 全集 + 多代理并行开发规则（2026-09-06）

**本文档是给执行 Agent（编排者）的自 contained 交付物。** 你没有参与此前的诊断与决策会话——全部决策依据在以下三份文档里，开工前必须通读：

1. `docs/plan-2026-09-06-home-dl-pagination-read-progress.md` —— 七个工作项（A1–A7）的根因诊断与定案（含 §7 复审细化、§8 审计联动）
2. `docs/audit-2026-09-06-webui.md` —— 全库审计报告（P0/P1 证据、威胁模型声明、正面结论清单）
3. `CONTEXT.md` —— 术语表（同步/配对/墓碑/data-dir 等词的精确含义）

（1、2 与执行日志是**未跟踪的工作文档**，用完即抛——见红线第 9 条，永不 commit。）

你的角色是**编排者（orchestrator）**：不开子代理亲自写代码是失误，但把未消化的任务卡原样丢给子代理同样是失误。规则见 §4。

---

## 1. 全局约束与红线（每个子代理的派发 prompt 必须附带本节）

1. **打码红线**：`PrivacyMaskFilter`、`privacy.mask_enabled`、API 脱敏链路（标题→#gid、路径截断、标签/评论清空、`X-Privacy-Mask` 头门控）**一行不改**。威胁模型定案（2026-09-06）：私有网络单用户，安全加固类放宽，但内容打码防的是**画廊站点风控**，属另一威胁模型，完整保留。
2. **Android 源码（`app/`）只读**：A7-0 调查可以读，任何任务不得修改 `app/` 与 `anotherviewer-core/`。
3. **实例访问纪律**：线上实例 `192.168.6.141:8081` 仅用于部署与打码形态下的 WebUI 走查；**不得用 curl/脚本抓取画廊内容、图片、文件**（打码开启下 Agent 访问 API 只会得到脱敏响应，这是预期）。
4. **凭据不入库**：SSH 密码由派发渠道提供，**绝不写入任何仓库文件、commit message、日志**。
5. **SQLite DDL 纪律**：实体新增列必须 `columnDefinition` 带 `default`（NOT NULL 无 default 的 ADD COLUMN 会被 SQLite 静默拒绝，仅 WARN——生产踩过坑，见 `HistoryInfoEntity.page` 列注释）。本批任务**零 schema 变更**，任何卡若发现必须加列，停下来升级给用户。
6. **不动无关代码**：不做顺手重构、不改公共样式变量契约（`--hamburger-clearance` 等）、不升级依赖。
7. **commit 风格**：对齐 `git log`（conventional commits + 中文主题行，如 `fix(web): …`）；执行期只本地 commit，**最终验收通过后统一 push**（2026-09-04 惯例）。
8. **基线分支**：直接在 `BiLi_PC_Gamer` 上按任务逐个 commit（沿用现状，不另开长期分支）。
9. **工作文档不 commit**：`docs/plan-2026-09-06-*.md`、`docs/audit-2026-09-06-*.md`、`docs/dev-plan-2026-09-06-*.md`、`docs/exec-log-2026-09-06.md` 是未跟踪的过程文档，**永不 git add**。执行期提交一律按卡精确 `git add <该卡文件清单>`，**禁止 `git add -A` / `git add .` / `git add docs/`**。历史 docs/ 下已提交的 plan-* 是旧惯例遗留，不新增、不删除。

---

## 2. 环境与命令速查

| 用途 | 命令 |
|------|------|
| 前端单测 | `cd web-frontend && npm test`（vitest run） |
| 前端类型检查 | `cd web-frontend && npm run typecheck`（vue-tsc --noEmit） |
| 前端构建 | `cd web-frontend && npm run build`（vue-tsc + vite） |
| 视觉回归 | `npm run test:visual` / 基线刷新 `npm run test:visual:update` |
| 多设备实机截图 | `node e2e/live-responsive.mjs`（详见该文件头注释） |
| 后端单测 | `./gradlew --configure-on-demand :anotherviewer-web:test` |
| 整包构建 | `./build.sh`（core → 前端 → bootJar；**前端 dist 打进 jar**，含 dist 新鲜度门） |

**部署目标**（凭据见派发渠道）：`ssh bob@192.168.6.141`，bob 免密 sudo。systemd 服务 `anotherviewer-web`，jar 位于 `/server/AnotherViewer/lib/app.jar`，data-dir `/server/AnotherViewer/data`（**绝不覆盖**），端口 8081（`--server.port=8081`）。更新流程：`./build.sh` → `scp` 到 `lib/app.jar.new` → `cp app.jar app.jar.bak-YYYYMMDD` → `mv` 原子替换 → `sudo systemctl restart anotherviewer-web` → `curl -s localhost:8081/api/v1/health` 验证（启动约 18s）。日志 `sudo journalctl -u anotherviewer-web`；远端无 sqlite3 CLI，验库用 python3。**注意**：仓库内 `deploy/anotherviewer-web.service` 与实机布局（/server 路径、8081）已漂移，以实机 `systemctl cat` 为准。

---

## 3. 依赖图与波次总览

```
W0 自举
 ├─ W1（5 卡并行）：W1-B1 首页翻转 | W1-B2 access log | W1-F1 进度修复 | W1-F2 增强图守卫 | W1-F3 搜索键守卫
 ├─ W2（3 卡并行）：W2-B1 下载分页后端 | W2-F1 下载分页前端 | W2-B2 后端性能批
 ├─ W3（峰值 7 并行）：
 │    W3-C1 行组件抽取（先行，其余 W3 卡依赖它）
 │    ├─ W3-F1 首页 | W3-F2 搜索 | W3-F3 历史 | W3-F4 收藏  （4 卡并行）
 │    ├─ W3-X1 契约卫生（与 C1 并行，卡内串行）
 │    └─ W3-F5 模式清理（4 视图卡之后）→ W3-F6 前端 P2 批（需 W3+A5 均合并）
 │    ‖ A5 设置域（单代理串行 A5-1→A5-2→A5-3，与 W2/W3 全程并行）
 │    ‖ A7-0 调查设计（只读，可最早启动，产出 A7 设计文档）
 ├─ A7 实施（W2 落地后）：A7-1+2 stamping/软删（单代理串行）→ A7-3 属主查询 → A7-T 同步回归
 └─ INT 集成收口：全量门 → 视觉基线一次性刷新 → 构建部署 → 实例验证清单
```

波次内并行、波次间串行；A5 与 A7-0 不受波次约束。**峰值并发 7 个子代理**（W3 中段），编排者此时只做调度与验收，不亲自写码。

---

## 4. 多代理协作规则

### 4.1 任务卡协议

每张卡（§5）按统一格式派发。子代理 prompt = 卡片全文 + §1 红线 + §2 中它需要的命令。**卡片必须原样完整复制**——子代理没有本对话上下文，缺一句背景它就会做出错误假设。

子代理的产出契约：①改动落在卡片锁定的文件集内；②跑完卡片指定的测试并附结果；③报告 diff 摘要与任何偏离卡片的决定；④**不 commit**（提交权在编排者）。

### 4.2 文件域锁

卡片的「文件（锁）」字段是该代理唯一可修改的文件集。**同一文件不得同时属于两张在飞卡**——这是并行安全的唯一保证，编排者派发前必须核对锁表。发现两张卡必须动同一文件时：串行化（后卡等前卡合并），或把交叉部分拆给先卡。默认共享工作树；仅当发生跨卡污染时启用 `git worktree` 隔离升级（每 worktree 需 `npm ci`，成本高，慎用）。

### 4.3 编排者循环

```
for wave in [W1, W2, W3, A7]:
    1. 核对波内卡片的文件锁两两不相交
    2. 并发派发（Agent tool 多 invoke 一条消息）
    3. 每卡归来：核对 git status 是否越锁、抽查关键 diff、跑该卡 scoped 测试
    4. 卡片 DoD 全过 → 按卡 commit（一张卡一个 commit，作者信息沿用仓库现状）
    5. 越锁/测试红 → 退回该代理修复或编排者亲自修，不放宽标准
波间：全量门（typecheck + npm test + gradle test）通过才进下一波
```

### 4.4 任务卡 DoD 通用标准

前端卡：`npm run typecheck` + `npm test` 全绿 + 卡内验收点逐条满足。后端卡：`:anotherviewer-web:test` 全绿 + 卡内验收点。涉及 UI 形态的卡不要求当场刷视觉基线（统一在 INT 刷一次）。

---

## 5. 任务卡全集（TODO list）

规模：S=小时级 M=半天级 L=一天级。

### W0 自举（编排者亲自，不开代理）

**T0 环境与基线** [S]
- 通读三份依据文档；`git status` 确认干净、HEAD=`a891f520` 之后；本地跑通 `npm test` 与 `:anotherviewer-web:test` 记录基线（若有既有红测试，记录在案并排除归因）。
- 在 `docs/` 创建 `exec-log-2026-09-06.md` 执行日志（每卡一行：ID/结论/commit hash）——同样是未跟踪工作文档，不 commit。

### W1（5 卡并行）

**W1-B1 首页语义翻转（A1）** [S] 依据 plan §1
- 文件（锁）：`GalleryService.kt`、`GalleryFeedServiceTest.kt`、`GallerySearchUrlTest.kt`
- 指令：`searchGallery` 空关键词分支重排——站点最新列表优先（现有 `buildSearchUrl("")` + searchCache 路径提前），EH DOWN（`availability.isBlocked()` 或上游异常）时回退 `searchLocalHistory`（保留 E2E-6 语义，现有「Local」用例改到 EH-DOWN 场景下断言）。新增用例：空关键词 + EH 可达 → verify SiteEngine 被调用且返回站点结果；空关键词 + 高级筛选参数 → 到达站点 URL（现状被本地历史分支吞掉，是顺带修复）。**不删 `searchLocalHistory`**。
- 验收：上述用例绿；既有非空关键词用例不回归。

**W1-B2 运行日志（A6）** [S] 依据 plan §6
- 文件（锁）：`application.yml`、`docs/deployment.md`
- 指令：`server.tomcat.accesslog` 启用（directory=`${anotherviewer.data-dir}/logs`、prefix=access、file-date-format=.yyyy-MM-dd、max-days=30、pattern=`%t %a "%r" %s %B %D`）。deployment.md 补两节：journald 持久化（部署机 `sudo mkdir -p /var/log/journal`）与慢请求分析工作流（`grep '/api/v1/' access.*.log | sort -kNF …` Top N 示例）。**目录必须落 data-dir 下**（systemd `ProtectSystem=strict` 只放行 data 可写）。
- 验收：本地 dev 启动后 `data/logs/access*.log` 出现且行含毫秒耗时字段；文档命令可复制执行。

**W1-F1 阅读进度修复（A3 前端部分）** [S] 依据 plan §3
- 文件（锁）：`ReaderView.vue`、`views/__tests__/ReaderView.spec.ts`
- 指令：`resolveStartPage` 与 `watch(route.params.page)` 守卫统一空串语义——`typeof x === 'string' && x.trim() !== ''` 且 `Number.isFinite` 才走深链分支，空串/缺失一律视为无深链。**根因**：vue-router 4.5 可选参数 `/reader/:gid/:page?` 缺省时 `params.page === ''`，`Number('')===0` 且有限，现状把所有无页参进入劫持为「深链第 0 页」，`detail.readProgress` 永不生效。spec 的路由 mock 改为镜像真实行为（`routeParams.page = ''` 而非 `delete`），新增回归用例：无页参 + `readProgress=12` → 起始页 12；页参 `'30'` 深链照旧；watcher 收到 `''` 不跳页。
- 验收：新用例绿；既有 F1 回写/节流/flush 用例不回归。
- 服务端 lastModified 部分**不在本卡**（归 A7）。

**W1-F2 增强图跨画廊守卫（审计 P1-4）** [S] 依据 audit P1-4
- 文件（锁）：`composables/useEnhancedImage.ts`（及其测试若存在，先 `ls composables/__tests__`）
- 指令：`img.onload/onerror` 闭包捕获发起时的 gid（或递增代次号），回调时与当前 gid 不符则只 `decrementPreloads` 不写 map。根因：`watch(gid)` 的 `resetState()` 清 map 后在途预加载完成仍写入旧画廊 URL，阅读器热替换错图。
- 验收：新增单测——预加载在途时切换 gid，完成后 map 不被写入。

**W1-F3 搜索页全局键停用守卫（审计 P1-5）** [S] 依据 audit P1-5
- 文件（锁）：`SearchView.vue`（仅键监听部分）
- 指令：`onGlobalKeydown` 的 add/remove 从 `onMounted/onBeforeUnmount` 移到 `onActivated/onDeactivated`（SearchView 在 App.vue 的 CACHED_VIEWS 内，停用实例曾在后台劫持 `/`、`f/F`）。注意防重复添加。**本卡不动 SearchView 其它部分**（A4 在 W3 大改它）。
- 验收：spec 增用例——deactivate 后 keydown 不再触发面板切换。

### W2（3 卡并行，W1 合并后启动）

**W2-B1 下载分页后端恢复（A2 后端）** [M] 依据 plan §2/§7.1
- 文件（锁）：`DownloadService.kt`、`DownloadController.kt`、`api/DownloadControllerTest.kt`、`service/DownloadServiceTest.kt`
- 指令：limit 上限 100_000 回滚为 500（`pageSize.coerceIn(1,500)`）；恢复服务端分页契约（page/pageSize/sort/q/regex，df382ee7 形态）；恢复**跨页全选 all 模式**（按筛选条件全集批量操作，替代 098f1c04 的显式 ids 退化——前端传 `all=true` + 筛选参数）。098f1c04 新增的 100_000 断言用例改回 500。
- 验收：分页钳制/all 模式用例绿；参考 `git show 098f1c04`（拆了什么）与 `git show df382ee7`（原先怎么做的）。

**W2-F1 下载分页前端恢复（A2 前端）** [L] 依据 plan §2/§7.1
- 文件（锁）：`DownloadView.vue`、`api/download.ts`、`views/__tests__/DownloadView.spec.ts`
- 指令：恢复分页条（63d20db5 形态：直点页码窗口 + 前后页 + 跳页 + PageUp/Down）、条数切换（默认 50，档 50/100/200）；对接 W2-B1 的服务端分页；全选改「当前页显式 + 跨页走 all 模式」混合语义；搜索词走服务端 q/regex（406648c9 形态）。**保留 tanstack 虚拟滚动不删**（W3-A4 要复用它）；保留排序模式（098f1c04 幸存，勿动）；保留 2026-09-05 之后的响应式修复。被 098f1c04 删的 370 行是主要参考（`git show 098f1c04 -- web-frontend/src/views/DownloadView.vue`）。
- 验收：分页条/条数切换/跳页/全选 all 的 spec 重建（098f1c04 删掉 263 行 spec 是重建底稿）；`npm run typecheck` 绿。

**W2-B2 后端性能批（审计 P2）** [M] 依据 audit P2-性能
- 文件（锁）：`HistoryService.kt`、`FavoriteService.kt`、`GalleryService.kt`（仅 searchCache builder）、`repository/HistoryInfoRepository.kt`、`repository/LocalFavoriteInfoRepository.kt`、对应测试
- 指令：①`listHistory` 的 q/regex 路径下沉 DB（title/titleJpn 派生查询已有先例，regex 可保留内存路径但仅对预过滤集）；②`listFavorites` 全表载入改 DB 分页（slot/q 过滤下沉或保留内存但仅对当页窗口）；③`searchCache` 补 `maximumSize(128)`（对齐 feedCache）。
- 验收：既有用例不回归；新增一条「万行级不分页全表」不出现的结构性断言（如 verify findAll 未被调用或 repository 层分页被调用）。

### W3（A5、A7-0 同期并行；W2 合并后启动）

**W3-C1 共享行组件抽取（A4 核心，先行卡）** [L] 依据 plan §4
- 文件（锁）：新增 `components/gallery/AppListRow.vue`（或按现有命名惯例）、新增 `composables/usePagedList.ts`、`DownloadView.vue`（重构为消费方）、`components/atoms/*`（如需图标）、相关新测试
- 指令：从 DownloadView 抽出通用单列行（缩略图 + 标题/副题 + 元信息行 + 可选角标 + 点击分区「缩略图→详情/主体→阅读」），下载特有字段（进度/状态/速率）用 prop/slot 表达；`usePagedList` 封装分页状态机（页码/条数/加载/stale 守卫/KeepAlive 页码还原——fullPath 分实例机制沿用 App.vue 现状）。DownloadView 切换后**行为与视觉不变**（它的 spec 是护栏）。
- 验收：DownloadView 既有 spec 全绿（必要时仅调整实现引用）；AppListRow 有独立 spec（各 slot/点击分区）。

**W3-F1 首页切换单列（A4）** [M] 依赖 W3-C1
- 文件（锁）：`HomeView.vue`、`views/__tests__/HomeView.spec.ts`
- 指令：GalleryList 换 AppListRow + usePagedList；分页条替换无限滚动（上游 `total = pages×25` 驱动页码）；**删除手搓虚拟窗口数学**（OVERSCAN/columns/rowHeight/measure/attachVirtualScroll 整族，分页后 50 行/页无需窗口化）；`goEhSession` 的 `/admin/eh` 链接改 `/settings/eh`（承接 A5）。空关键词数据源已由 W1-B1 翻转，本卡只管形态。
- 验收：列表/分页/搜索/筛选/feed 模式 spec 更新后全绿。

**W3-F2 搜索切换单列（A4）** [M] 依赖 W3-C1
- 文件（锁）：`SearchView.vue`、`views/__tests__/SearchView.spec.ts`
- 指令：同 F1 模式；**删除 localStorage `search-view-mode` 键及读写**（迁移：忽略旧值即可）；GalleryCard 引用移除；保留 W1-F3 的键监听修复与全部搜索/筛选逻辑。
- 验收：搜索 spec 更新后全绿。

**W3-F3 历史切换单列（A4）** [S] 依赖 W3-C1
- 文件（锁）：`HistoryView.vue`、spec。指令：同 F1 模式（服务端分页 W2-B2 已备）。验收：spec 绿。

**W3-F4 收藏切换单列（A4）** [S] 依赖 W3-C1
- 文件（锁）：`FavoriteView.vue`、spec。指令：同 F1 模式。验收：spec 绿。

**W3-F5 模式体系清理（A4 收尾）** [M] 依赖 F1–F4
- 文件（锁）：`components/gallery/GalleryList.vue`、`GalleryGrid.vue`、`GalleryCard.vue`（先 grep 确认仅 GalleryList 引用）、`stores/preferences.ts`（listMode 停写）、相关 spec
- 指令：删除 GalleryList/GridCard 双档组件与模式切换工具条；Web 侧停读停写 `prefs.general.listMode`（**后端 PreferenceController 字段保留**——App 仍同步它，Web 只是不再消费）；grep 清理残留引用。
- 验收：typecheck 绿；`grep -r listMode web-frontend/src` 仅剩无害注释或零命中。

**W3-F6 前端 P2 修补批（审计前端项收口）** [M] 依赖 W3 全卡 + A5 合并（router/index.ts 锁冲突，必须殿后）
- 文件（锁）：`stores/preferences.ts`、`api/client.ts`、`router/index.ts`、`public/sw.js`、`views/SearchView.vue`、`views/DownloadView.vue`、`views/HistoryView.vue`、`views/HomeView.vue`、`views/FavoriteView.vue`、相关 spec
- 指令（六项，均为 audit P2，逐项独立 commit-able）：①preferences store 保存失败后 dirty 永真、后续 load 全被丢弃——改为保存失败允许下一次 load 覆盖并提示；②SearchView loadingMore 复位对齐 HomeView F5（finally 无条件复位）；③对话框 Escape 监听与 400ms 搜索防抖在 KeepAlive 停用/卸载后残留——移 onDeactivated/清理 timer（History/Download/Home/Favorite 四处）；④SW 对跨域 opaque 图片永远判不新鲜的双写——opaque 条目跳过缓存写仅离线兜底；⑤401 拦截器改 `router.replace({name:'Login', query:{redirect: to.fullPath}})` 并取消在途请求；⑥路由守卫的 `/auth/status` 模块级缓存（带 TTL，登出失效）。
- 验收：各新增/改造用例绿；typecheck 绿。

**W3-X1 契约卫生（审计契约项）** [S] 与 C1 并行（卡内顺序执行，锁不与 C1 冲突）
- 文件（锁）：`contracts/openapi.yaml`、`api/GalleryController.kt`、`service/GalleryService.kt`（仅删 getHistory/getLocalFavorites 两方法）、`api/DownloadController.kt`（删 import-zip）、`service/DownloadZipImportService.kt`（整文件删）、相关测试
- 指令：①spec 补 `POST /download/restart-all`、`GET/POST /privacy/mask`、`POST /site/proxy` 三端点（对齐既有实现的参数/响应）；②删除零调用死端点：`GET /gallery/history`、`GET /gallery/favorites`（连带 GalleryService 两方法——顺带消灭 audit P1-3 的墓碑过滤缺陷）与 `POST /download/import-zip`（连带 Service 与测试）；③Archive/Torrent/Process/Cache 四组控制器**保留不动**（spec 冻结+测试在，默认决策见 §6）。
- 验收：`:anotherviewer-web:test` 绿；openapi 无引用已删路径；删除项在 commit message 列明。

### A5 设置域（单代理串行三步，与 W2/W3 并行）

依据 plan §5。**域内耦合高，不拆多代理**；文件锁：`router/index.ts`、`views/settings/*`、`views/admin/*`、`components/layout/NavigationDrawer.vue`、`App.vue`、`views/__tests__/Admin*.spec.ts`、`views/__tests__/Settings*.spec.ts`。

**A5-1 路由与布局合并** [M]：`/admin` 子树并入 `/settings`（分组：偏好=general/reader/privacy/transfer 原路径；服务器=download/filter-slots/server/backup/devices/eh/access/processing/advanced/about → `/settings/server/*` 等），旧 `/admin/*` 全部 redirect；SettingsLayout 重写为分组侧栏（宽屏 ≥960px 双栏保留）+ AdminLayout 删除。
**A5-2 页面平移与导航** [M]：admin 页面组件移入 settings 域（文件移动或仅改路由引用，取 diff 小者）；NavigationDrawer 去掉「管理面板」入口与分隔线（NAV_TARGET_PATHS 收敛）；App.vue 路由→导航项映射更新；各 Admin spec 的路径断言更新。
**A5-3 窄屏层级导航（Android 式）** [M]：`/settings`（exact）窄屏渲染分组索引页（大行列表，44px 触控行高，复用抽屉行形态），宽屏 `setup` 内 matchMedia 一次性 `router.replace` 到默认子页；子页页头返回箭头仅窄屏可见，`history.back()` 优先、无历史兜底 push 根列表（GalleryDetailView.goBack 的 C5 模式）。e2e/live-responsive.mjs 补设置域截图项。
- 验收（域级）：旧 `/admin/eh` 深链可达（redirect）；导航高亮正确；窄屏进入/返回链路可用；全部设置域 spec 绿。

### A7 同步 stamping（W2 合并后；A7-0 可立即启动）

依据 audit P0-1/P1-1、plan §8。**这是本批风险最高的域**——当前 App↔Web 同步是工作的，破坏它等于回退。全程要求每步合并后跑 SyncService 相关测试。

**A7-0 调查与设计（只读，最早可启动，与 W1 并行亦可）** [M]
- 文件（锁）：新增 `docs/plan-a7-design.md`（唯一产出物）
- 指令：调查并回答三个设计问题——①Android 端 pull 如何感知**服务端发起的 history 删除**（读 `app/src/main/java/com/hippo/anotherviewer/webui/` 的同步实现：pull 后本地差分逻辑），据此决定 `clearHistory` 的传播方案（墓碑化 vs 依赖客户端快照差分）；②favorite/download 的 Web 删除改软删后，`/favorite/list` 等列表端点的 `deleted` 过滤是否完备（对照 HistoryService.listHistory 的过滤）；③stamping 的注入点（Spring SecurityContext 直取 vs service 参数传递，倾向前者零侵入）。产出含实施清单的设计文档，**不改任何代码**。
- 验收：设计文档三问有代码行号级证据；提交给编排者评审后才放行 A7-1。

**A7-1+2 stamping 与软删（单代理串行）** [L] 依赖 A7-0 评审
- 文件（锁）：`HistoryService.kt`、`FavoriteService.kt`、`GalleryService.kt`（addToHistory/createQuickSearch/deleteQuickSearch）、`DownloadService.kt`（addDownload）、`service/GalleryService.kt` 相关测试、`SyncService.kt`（adopt 标志重触发）
- 指令：所有 Web 本地写统一 stamping——`username = SecurityContext authentication.name`（**已核实**：require_auth=false 时 AuthTokenFilter 全员置 "default"，SyncController push/pull 同源取 authentication.name，两种认证模式下与 App 推送天然一致，无需特判）、`lastModified = now`；`removeFavorite` 等软删实体删除改 `deleted=true + lastModified bump`；`clearHistory` 按 A7-0 结论改造；一次性删除 `sync.ownership.adopted.*` 标志语义或提供重触发，收容存量 NULL 行。
- 验收：新增集成用例——Web addFavorite→App pull 可见；Web removeFavorite→App pull 收到删除；stamping 后行带 username/lastModified。

**A7-3 属主维度查询** [M] 依赖 A7-1+2
- 文件（锁）：`repository/HistoryInfoRepository.kt`、`LocalFavoriteInfoRepository.kt`、`DownloadInfoRepository.kt`、`SyncService.kt`（调用点）、`HistoryService.kt`/`GalleryService.kt`（调用点）
- 指令：sync 合并路径的 `findByGid` 改 `findByGidAndUsername`（或 List 返回 + ownedBy 过滤）——多用户同 gid 时派生查询爆炸是 audit P1-1。**注意**：`GalleryService.getGalleryDetail` 等读路径的 `findByGid` 是「本地可见性」语义（单用户模型下理应看到全部本地行），只改 sync 写合并路径与 addHistory 的 update 路径，读路径保持——在测试里固化这个区分。
- 验收：新增多用户用例（两 username 同 gid push 不炸、属主隔离正确）。

**A7-T 同步回归补强** [M] 依赖 A7-3
- 文件（锁）：`service/SyncServiceTest.kt`（或其所在）
- 指令：补三类回归锁——web-write→pull 可见性、删除传播端到端、多用户同 gid；把 audit「已核实无问题」清单中的关键不变量（page 取 max、墓碑复活路径、tie-break）固化为断言。
- 验收：全绿；`:anotherviewer-web:test` 全量绿。

### INT 集成收口（编排者亲自 + 按需 1-2 代理）

**INT-1 全量门** [S]：`npm run typecheck && npm test && ./gradlew --configure-on-demand :anotherviewer-web:test`；红则定位到卡退回修复。
**INT-2 视觉基线一次性刷新** [S]：`npm run test:visual:update`（覆盖 A2 下载分页/A4 全站列表/A5 设置域形态）；随后 `npm run test:visual` 确认稳定；`node e2e/live-responsive.mjs` 本地起 dev 实例过一遍矩阵。
**INT-3 构建与部署** [S]：`./build.sh` → 按 §2 流程部署到 192.168.6.141（备份先行）；部署机 `sudo mkdir -p /var/log/journal`；重启后 health 验证 + `data/logs/access*.log` 出现。
**INT-4 实例验证清单** [M]（打码形态下走查，禁抓内容）：
- [ ] 首页默认站点最新列表（非历史混杂）；空关键词+筛选生效
- [ ] 下载页：分页条/条数/跳页/排序不回归/跨页全选 all 模式
- [ ] 阅读进度：读到 N 页→退出→重开回到 N；另一设备（App）增量同步能看到 N
- [ ] 全站列表单列形态（首页/搜索/历史/收藏）；KeepAlive 页码还原
- [ ] 设置：单入口、分组、旧 /admin/* 深链 redirect、窄屏进入/返回
- [ ] Web 加收藏→App 可见；Web 删收藏→App 同步删除（A7 核心验收）
- [ ] access log %D 有值；慢请求 Top N 命令跑通
- [ ] 打码开关翻转后脱敏/明文形态均正常（红线回归）

---

## 6. 默认决策表（编排者遇未列事项按此执行，不等待用户）

| 事项 | 默认决策 |
|------|---------|
| DownloadView 虚拟化 | 保留（A4 复用），不做 §7.1 的退役简化 |
| 分页条数档位 | 50（默认）/100/200 |
| import-zip 死端点 | 删除（W3-X1） |
| Archive/Torrent/Process/Cache 控制器 | 保留不动（spec 冻结+测试在，删除风险>收益） |
| /gallery/history、/gallery/favorites | 删除（W3-X1，连带消灭墓碑过滤缺陷） |
| SmbBackupView 是否收进设置 | 不收（独立向导保持顶层路由） |
| 需要新增 DB 列 | **禁止**——升级给用户 |
| 测试基线有既有红 | 记录进 exec-log、排除归因，不为转绿修改无关测试 |
| 子代理越锁改文件 | 退回：revert 其越锁改动，由编排者把改动归入正确卡片或另开卡 |

## 7. 交付物定义

全部完成后：`BiLi_PC_Gamer` 分支上按卡一 commit 的历史（信息风格对齐 git log）+ 更新的视觉基线 + `docs/exec-log-2026-09-06.md` 执行日志 + 线上 192.168.6.141:8081 已部署并通过 INT-4 清单。最终 push 在用户确认 INT-4 后执行。
