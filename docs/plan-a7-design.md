# A7 设计文档：Web 本地写统一 stamping + 软删改造（P0-1 / P1-1）

> 任务卡 A7-0 调查与设计产出。依据 `docs/audit-2026-09-06-webui.md`（P0-1、P1-1）与
> `docs/dev-plan-2026-09-06-execution-handoff.md` §A7。本卡只读调查，未改任何代码；
> 后续 A7-1+2 / A7-3 / A7-T 按本文实施清单执行。
>
> 红线重申：`PrivacyMaskFilter`、`privacy.mask_enabled`、API 脱敏链路一行不改；
> `app/` 与 `anotherviewer-core/` 只读（本设计 App 端零改动，理由见 §1.5）。

---

## 0. 背景速览（断裂点回顾，行号已核）

| 断裂点 | 位置 | 后果 |
| --- | --- | --- |
| addFavorite 落库不写 username/lastModified | `FavoriteService.kt:102-140`（:111-123 建实体、:124 save，无 stamp） | username=null、lastModified=0 |
| addHistory insert 同族 | `HistoryService.kt:89-103`、`GalleryService.kt:600-608` | 同上；update 分支（:82-87 / :590-598）也不 bump lastModified |
| addDownload 同族 | `DownloadService.kt:237-251` | 同上 |
| createQuickSearch 同族 | `GalleryService.kt:656-668` | 同上 |
| removeFavorite 物理删行 | `FavoriteService.kt:144`（`favoriteRepository.delete`） | App 下次 push 同 gid 时 `raw==null → save`，union-merge 重建，Web 删除被静默撤销 |
| clearHistory 物理删行（连墓碑） | `HistoryService.kt:108`（`deleteAll()`） | 删除永不传播（§1.4 详证） |
| adoptNullOwnership 一次性短路 | `SyncService.kt:209-210`（server_config 标志），注释 :206-208 明言「导入/还原均显式落 username，不会再生 NULL 行」 | 该假设被上述 Web 写路径违反；标志置位后新 NULL 行永不被收养 |

实体三列默认值已核（`LocalFavoriteInfoEntity.kt:33-39`、`DownloadInfoEntity.kt:44-50`、
`HistoryInfoEntity.kt:27/42/45`、`QuickSearchEntity.kt:52-58`、`DownloadLabelEntity.kt:27-33`）：
`lastModified=0`、`deleted=false`、`username=null`，且各表都有 `(username, last_modified)` 索引
（H-3 增量 pull 用）。

对照的「正确范例」：`DownloadUploadService.initUpload`（`DownloadUploadService.kt:43`，controller
传 username，:80 `this.username = username`、:83 `lastModified = now`）；`EhImportService` 导入各表
均显式 stamp（如 :262-263、:306-311、:370-371）。adopt 注释的假设对这两条路径成立，只对
Favorite/History/Download/QuickSearch 的 REST 写路径失效。

---

## 1. 问题①：Android 端 pull 如何感知服务端发起的删除？

### 1.1 App 端删除感知有且只有两条通道，且方向相反

App 端引擎 `WebUiSyncEngine.java`（app/src/main/java/com/hippo/anotherviewer/webui/）：

**通道 A：本地删除 → push 墓碑**（CONTEXT.md:21-22 的「快照/待删集合」术语属实）。
`detectDeletions`（`WebUiSyncEngine.java:1396-1401`）：

```java
private <T> Set<T> detectDeletions(Set<T> snapshot, Set<T> pending, Set<T> current) {
    Set<T> deletions = new LinkedHashSet<>(snapshot);
    deletions.addAll(pending);
    deletions.removeAll(current);
    return deletions;
}
```

上次成功同步快照 ∪ 待删集合 − 当前本地 key 集合 = 待传播删除（:477-484 逐实体调用；
:497-504 W5 检测即落盘）。这些 key 以 `deleted=true` 墓碑随 push 发出（收藏 :857-866 soft、
历史 :883-892 hard、下载 :829-838 soft、书签 :925-934 hard）。
**注意方向：这个差分只发现「本地删了的」，用于 push；它从不做「服务端集合 vs 本地集合」的反向差分。**

**通道 B：服务端删除 → pull 到达的 `deleted=true` 记录**。pull 之后逐实体 apply，
删除的唯一判定条件是记录上的 deleted 标志：

- `applyFavorites` :1054-1071 — `fav.deleted` →（§3.8 优先级守卫 `honorSoftTombstone` :1057 通过后）
  `mStore.removeLocalFavorites(fav.gid)`（:1067）；
- `applyHistory` :1102-1107 — `hist.deleted` → `mStore.removeHistoryByKey`（:1103），**无优先级守卫，无条件删**；
- `applyDownloads` :1138-1152 — `dto.deleted` → `mStore.removeDownloadInfo`（:1147）；
- `applyBookmarks` :1188-1194 — `dto.deleted` → `mStore.removeBookmarkByGid`（:1190），无条件删；
- `applyFilters` :1234-1242、`applyQuickSearches` :1293-1304、`applyDownloadLabels` :1334-1345 同构。

**apply 阶段没有任何「响应里缺席 = 已删除」的推断**：apply 只遍历服务端返回的记录，逐条 upsert
或删；记录不在响应中不会被解释成任何事。pull 是增量水位（:610 `mTransport.pull(config, since)`；
`WebUiApiSyncTransport.java:39-40` 透传 since；服务端 `SyncService.kt:117-125` 走
`findByUsernameAndLastModifiedGreaterThan(username, since)`）。**一行被服务端悄悄物理删除后，
它在增量响应里只是「消失」，lastModified 不存在 > since 的时刻，App 永远收不到任何信号。**
即使 App 重装做 since=0 全量 pull，apply 也只会把响应里的记录 upsert 进来，不会删除本地多出的行。

### 1.2 软删实体（收藏/下载）：墓碑行确实下发，App 收到即删本地行

服务端 pull 的查询**不过滤 deleted**（`SyncService.kt:119-125`，`findByUsername` /
`findByUsernameAndLastModifiedGreaterThan` 均无 deleted 条件），DTO 映射 :138-144 原样带出
`deleted = deleted`（`toSyncFavoriteDto` :1045-1054 等）。即：**墓碑行（deleted=true 的行）就是
删除事件的载体，随全量/增量 pull 下发给 App**。

App 侧不存在「墓碑行入库后泄漏到界面」的问题：App 本地存储是 GreenDAO/SiteDB，没有 deleted
列，收到墓碑的动作就是物理删除本地行（§1.1 引用的 remove* 调用）。数据源永远是本地库，
服务端列表不直接驱动 UI。**因此 App 端无需任何配合改动；「别把墓碑当数据显示」的过滤义务
全部在 Web 侧 REST 端点（= 问题②）。**

补充（复活路径，证明模型自洽）：墓碑被 §3.8 优先级守卫拒绝时（非优先端删、本机是优先端且仍持有），
App 丢弃该 key 的 push ledger 条目（:1062-1063、:1143-1144），下一轮把活记录重推，服务端
`mergeFavorite` 的「incoming live 复活墓碑」分支（`SyncService.kt:276-281`）落回 deleted=false。

### 1.3 「硬删」实体（历史/书签）：服务端同样是墓碑行传播——只是 App 收到后删得干脆

App 注释里 history/bookmark 是 "hard delete"（`WebUiSyncEngine.java:80-81`「hard for history and
bookmarks (the server deletes the record)」），指的是**服务端在收到 App push 的删除墓碑后不再向
第三方转发一条活记录**。但服务端实现上并没有物理删行——`SyncService.kt` 的注释与代码是决定性证据：

```kotlin
// mergeHistory, SyncService.kt:322-335
if (incoming.deleted) {
    // 软删: 不真删行，bump lastModified 使 since>0 的增量 pull 能取到墓碑。
    // tombstone 实体：删除任何策略下传播（§3.8），v1 行为保持不变。
    if (existing != null) {
        existing.deleted = true
        existing.lastModified = maxOf(existing.lastModified, incoming.lastModified)
        historyRepository.save(existing)
        ...
    } else if (raw == null) {
        // 未知 gid 也存墓碑，删除同样能传播到其他设备
        historyRepository.save(incoming.toHistoryEntity(username))
        ...
```

`mergeBookmark` :443-456 逐字同构。对应测试固化：SyncServiceTest
`history delete keeps a tombstone row and bumps lastModified`（:885）、
`history tombstone reaches an incremental pull after the bump`（:919）、
`full pull returns history tombstones with deleted flag`（:930）、
`bookmark delete keeps a tombstone row and bumps lastModified`（:622）等。

所以「硬删」的真实含义只是：**App 收到这类墓碑时无条件删除本地行（无 §3.8 优先级守卫，
`WebUiSyncEngine.java:214-219` 类注释自证「Tombstone entities (history/bookmark) always propagate
and bypass this guard」）**；服务端侧的传播机制与软删实体完全一致——留墓碑行 + bump lastModified。
对「硬删实体墓碑化」的回答：**HistoryInfoEntity/BookmarkInfoEntity 本来就有 deleted 列且服务端
已在用（`HistoryInfoEntity.kt:42`），墓碑化对它们零 schema 改动，就是把 merge 路径已有的做法
搬到 Web 本地删除路径上。**

### 1.4 结论：clearHistory 必须墓碑化，「依赖客户端差分」不可行

**明确结论：`clearHistory` 改为逐行墓碑化（`deleted=true, lastModified=now`），不能依赖 App
快照差分。** 证据链：

1. **增量拉取不可见**：`deleteAll()`（`HistoryService.kt:108`）后没有任何行满足
   `lastModified > since`，通道 B 无信号（§1.1）。
2. **App 差分方向相反**：snapshot/pending 差分只发现「本地删了、服务端还有」的 key（通道 A，
   `WebUiSyncEngine.java:1396-1401`）；不存在「服务端没了、本地还有」的检测代码。App 下次
   pull 不会因为服务端历史集合变空而删任何本地行。
3. **即使做全量 pull 也不删**：apply 是纯 upsert（§1.1）；反而更糟——App 重装后 push ledger
   为空 → 全量 push 本地历史 → 服务端 `mergeHistory` 对每个 gid `raw==null → save`
   （`SyncService.kt:337-342`）逐条复活，**清空被完全撤销**，与 removeFavorite 的复活是同一机理。
4. **服务端自己的删除传播就是墓碑**（§1.3），clearHistory 没有理由例外。

改造形态：对（当前用户的）全部 `deleted=false` 历史行执行 `deleted=true; lastModified=now()`
（单事务批量 UPDATE；username 为 NULL 的存量行顺带落当前用户，等价一次就地收养）。
规模评估：本地历史行数量级为千~万，SQLite 单事务批量 UPDATE 可接受。

两个必须写进实现的注意点：

- **墓碑无 GC**：与 mergeHistory 落的墓碑一致，行永久留存。不建议 A7 引入自动清理——离线超过
  清理窗口的设备会永久错过删除（pull 不到墓碑、push ledger 里 key 还在但记录未变，删除丢失）。
  如将来要清，窗口必须远大于最大设备离线时长，且要在设计文档里单独立项。
- **多用户作用域**：`deleteAll()` 现状是无差别清库。墓碑化时应只清当前用户的行
  （`findByUsername(name)`）；require_auth=false 时全员即 "default"，效果等同清库。
  NULL 存量行在 require_auth=false 下属于 "default"，可一并墓碑化。

### 1.5 App 端零改动声明

App 引擎已完整支持处理上述所有墓碑（含 history/bookmark 的 `deleted=true`，§1.1/§1.3 引用），
`app/` 不需要也不允许改动。A7 的全部改动落在 anotherviewer-web。

---

## 2. 问题②：favorite/download 改软删后，列表端点的 deleted 过滤是否完备？

### 2.1 已过滤的（对照基线，改造后保持）

| 位置 | 证据 |
| --- | --- |
| `HistoryService.listHistory` 全量路径 | `HistoryService.kt:34` `findAllByOrderByTimeDesc().filter { !it.deleted }`（注释 :32-33 明言「墓碑行不列进 REST 列表」） |
| `HistoryService.listHistory` q/regex 路径 | `HistoryService.kt:42-43` `filter { !it.deleted }` |
| `HistoryService.listHistory` 分页路径 | `HistoryInfoRepository.kt:34-36` `findHistoryPaged` JPQL `where h.deleted = false` |
| `FavoriteService.listFavorites` | `FavoriteService.kt:39` `filter { !it.deleted }`（注释 :36-38，R4-17） |

测试固化：`HistoryServiceTest.kt:213/:225`、`FavoriteServiceTest.kt:252/:264/:276`。

### 2.2 未过滤的（改造后必须补，逐处清单）

**收藏（favorite）——软删后新增墓碑行会从这些路径漏出：**

| # | 位置 | 问题 |
| --- | --- | --- |
| F1 | `GalleryService.getLocalFavorites` — `GalleryService.kt:624` `findAllByOrderByTimeDesc()` 无过滤 | GET `/api/v1/gallery/local-favorites`（`GalleryController.kt:124`）会列出墓碑收藏 |
| F2 | `GalleryService.getGalleryDetail` favorite 分支 — `GalleryService.kt:428` `findByGid` | 已删收藏仍可开详情；且墓碑行字段多被 `applyFavoriteFields`（`SyncService.kt:848-872`）从空 DTO 清空，会渲染垃圾详情 |
| F3 | `FavoriteService.addFavorite` — `FavoriteService.kt:109-110` `findByGid` 存在性检查 | 墓碑行存在 → `return false`，**Web 永远无法重新收藏该 gid**。需引入复活语义（对齐 `mergeFavorite` 的「incoming live 复活墓碑」分支 `SyncService.kt:276-281`：deleted→false + 更新字段 + stamp + 返回 true） |

**下载（download）——DownloadService 与 DownloadInfoRepository 全线无 deleted 过滤：**

| # | 位置 | 问题 |
| --- | --- | --- |
| D1 | `DownloadService.listDownloads` — `DownloadService.kt:138-145`：`searchDownloads`/`countSearchDownloads`（`DownloadInfoRepository.kt:34-51` SEARCH_WHERE 无 deleted）、`findByLabel`/`countByLabel`（:15/:26 附近）、`findAll(pageable)`/`count()`（:144） | 列表与 total 计入墓碑 |
| D2 | 正则筛选 — `DownloadService.kt:183-195` `regexMatched` → `findTitlesByLabel`（`DownloadInfoRepository.kt:71-80` 无 deleted） | regex 路径漏墓碑 |
| D3 | 批量全集 — `DownloadService.kt:467-478` `resolveBatchIds` → `findAllIdsBy`（`DownloadInfoRepository.kt:55-66` 无 deleted） | `all=true` 跨页全选会把墓碑算进批量 Start/Stop/Delete/Move 目标 |
| D4 | `DownloadService.getDownloadInfo` — :222-224 `findById` | WebUI 详情/活动下载仍返回墓碑行 |
| D5 | `DownloadService.startAllDownloads` — :353-356 `findByState(0)` | 墓碑行 state 保留原值，被当待启动任务 |
| D6 | `DownloadService.restartAllDownloads` — :372 `findAll()` | 「全部下载」遍历墓碑行 |
| D7 | `DownloadService.pauseAllDownloads` — :423-426 `findByState(1)`+`findByState(2)` | 同 D5 |
| D8 | `DownloadService.getCompletedDownloadCount`/`getFailedDownloadCount` — :551-553 `countByState(3)`/`countByState(4)` | stats 计入墓碑（消费方 `MetricsController.kt:88/:154-155`、`DownloadZipImportService.kt:94`） |
| D9 | `DownloadService.completeIfVerified` — :407-421 `findByGid` | 墓碑行被磁盘校验「完成化」（复活表象）；`DownloadZipImportService.kt:163-179` 同构 |
| D10 | `GalleryService.getGalleryDetail` download 分支 — `GalleryService.kt:403` `findByGid` | 已删下载仍作为本地详情源（阅读器回退入口） |
| D11 | `FavoriteService` 下载行回写 — :135-138（add）/ :150-153（remove）`downloadRepository.findByGid` | 回写 favoriteSlot 可能落在墓碑行（次要，建议一并过滤） |
| D12 | 下载标签 — `DownloadService.listDownloads` :133 `labelRepository.findAll()`、`createLabel` :482-492、`deleteLabel` :494-498 | downloadLabel 是同步软删实体（契约 §3.7，`SyncService.kt:635-694`），Web 侧列表不过滤 deleted、删除是物理删 |

**同族既有漏洞（历史/快搜墓碑——A7 前就存在，clearHistory 墓碑化后会放大，建议随卡修）：**

| # | 位置 | 问题 |
| --- | --- | --- |
| H1 | `GalleryService.getHistory` — `GalleryService.kt:613` `findAllByOrderByTimeDesc()` 无过滤 | GET `/api/v1/gallery/history` 列出墓碑（设备端删除历史后即触发） |
| H2 | `GalleryService.searchLocalHistory` — :375 `findByTitleContaining...`、:379 `findByCategory...` 无过滤（仅 :377 `findHistoryPaged` 路径有） | 本地搜索/分类路径漏墓碑 |
| H3 | `GalleryService.getGalleryDetail` history 分支 — :409 `findByGid`；`readProgressOf` — :587 `findByGid` | 墓碑历史行仍出详情/进度 |
| Q1 | `GalleryService.getQuickSearches` — :636 `findAllByOrderById()` 无过滤 | 设备端删快搜后墓碑以普通快搜出现在 GET `/api/v1/gallery/quick-search` |

**必须保持不过滤的（勿改）**：`SyncService.pull`（`SyncService.kt:119-125`）——墓碑正是下发给
App 的载体；`SyncService.merge*` 的按 key 查询（:256/:320/:380/:441/:506-508/:577/:639）——必须
看到墓碑行才能做删除/复活仲裁。可选决策点：`SyncService.status` 的 entityCounts（:169-177
`countByUsername` 不过滤墓碑）——建议保持（反映同步存储面），文档化即可。

---

## 3. 问题③：stamping 的注入点选型

### 3.1 现有先例：全部是 controller 层取，无一例 service 层直取

grep 全库（main）：`SecurityContextHolder` 只出现在 `AuthTokenFilter.kt:26/:37`（写入）；
`Authentication` 参数注入出现在 7 个 controller：`SyncController.kt:30`（`syncService.push(request,
authentication.name)`）、:42（pull）、:47、:52；`ImportController.kt:47`；`DownloadUploadController.kt:42`；
`PreferenceController.kt:15/:23`；`CommentController.kt:24`；`AuthController.kt:54/:114/:120/:152`。
**service 层零先例。** service 收 username 全部是显式参数（`SyncService.push(request, username)`、
`DownloadUploadService.initUpload(gid, request, username)`、`EhImportService.importEhViewer(..., username)`）。

### 3.2 方案对比与推荐

| | A：service 层直取 SecurityContext | B：controller 取了传参 |
| --- | --- | --- |
| 签名影响 | **零**——HistoryService/FavoriteService/DownloadService/GalleryService 公开方法签名不变 | 15+ 端点签名 + service 方法全改；`GalleryService.getGalleryDetail:423` 内部调 `addToHistory`，参数还要穿透内部调用链 |
| 测试影响 | 纯 Mockito 单测里 context 为空（§3.3，可用 Provider 化解） | `HistoryServiceTest` 等 5 个直接 `new Service(mock...)` 的测试、`HistoryModePassthroughTest` 构造点、`HistoryControllerTest`/`FavoriteControllerTest` 的参数断言全部要改 |
| 先例一致性 | 无先例（但有 AuthDefaultsTest 直读先例） | 与现有 7 controller 一致 |

**推荐：A，落成「极小 Provider bean」形态**（兼顾 A 的零签名侵入与可测性）：

```kotlin
// web/config（或 web/service）
@Component
class CurrentUsernameProvider {
    fun currentUsername(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "default"
}
```

- 受 stamping 的 service 构造注入它；纯 Mockito 测试传 `{ "test-user" }` 之类的 fake，
  完全不碰 SecurityContextHolder；
- fallback `"default"` 只是防御（@Scheduled 线程、worker 线程）：正常 /api/** 请求链上
  authentication 必不为 null（§3.4）；
- 这个 fallback 与 require_auth=false 下的默认主体同名，即使某条非请求线程路径意外走到，
  落的也是单用户部署的主体，不会制造新 NULL 行。

### 3.3 测试环境行为（逐类核实）

- **纯 Mockito 单测**（`HistoryServiceTest`、`FavoriteServiceTest`、`DownloadServiceTest`、
  `GalleryServiceTest`、`HistoryModePassthroughTest` 均直接构造 service）：
  `SecurityContextHolder.getContext().authentication == null`，直取 `.name` 得 null。
  若不引入 Provider，测试须手写
  `SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken("default", null))`
  （@BeforeEach）+ `SecurityContextHolder.clearContext()`（@AfterEach，`AuthDefaultsTest.kt:40` 已有
  clearContext 收尾先例）。注意 **`@WithMockUser` 在纯 JUnit+Mockito 测试无效**（需要
  SpringExtension 的 TestExecutionListener）。
- **standalone MockMvc**（`HistoryControllerTest.kt:26-29`、`FavoriteControllerTest.kt:28-31`
  `MockMvcBuilders.standaloneSetup`）：不跑 security filter 链，`request.getUserPrincipal()` 为
  null——正因为推荐方案 A（service 层取、controller 无 Authentication 参数），**这两组测试完全不受影响**。
- **@WebMvcTest + 真实 SecurityConfig**（`SyncControllerTest` 模式）：AuthTokenFilter 生效。
  require_auth=false（ServerConfigService 默认，`AuthDefaultsTest.kt:71` 断言 `authentication?.name ==
  "default"`）→ service 层直取得 "default"；require_auth=true 场景带 Bearer
  （`SyncControllerTest.kt:111` `validateToken("valid-token") → "alice"`）→ 得登录名。
  端到端验收用例可在此层断言「default 用户 POST /favorite/add 后行 username=="default"」。

### 3.4 filter 链可靠性（require_auth=false → "default"）

- `AuthTokenFilter` 经 `SecurityConfig.kt:36` `addFilterBefore(..., UsernamePasswordAuthenticationFilter)`
  挂链，先于授权与 controller 执行；require_auth=false 时**每请求无条件**置
  `UsernamePasswordAuthenticationToken("default", ...)`（`AuthTokenFilter.kt:24-29`），require_auth=true
  时置校验后的登录名（:30-38），无 token 的 /api/** 请求被授权层挡成 401（`SecurityConfig.kt:85`），
  到不了 service。
- 链上无清空 context 的环节：`SessionCreationPolicy.STATELESS`（:35）不落 HttpSession；Spring
  Security 6 的 context 清理发生在请求收尾（controller/service 之后），请求内 ThreadLocal 稳定。
  已核 `WebConfig`/`SpaWebConfig`/`PrivacyMaskFilter` 均不动 SecurityContext（红线文件本来就不碰）。
- **两个已知边界**（写进实现注意事项）：
  1. **ASYNC dispatch 不传播 context**（`SecurityConfig.kt:66-73` 注释自证：STATELESS 下 REQUEST
     结束即清空，AuthTokenFilter 跳过 async dispatch）。已核 A7 涉及的四个 service 的写方法均在
     普通同步 @RestController 路径上，无 StreamingResponseBody+写库组合 → 不受影响；实现时保持
     「写同步表的方法不放进流式响应体」即可。
  2. **worker 线程无 context**：`DownloadService` 的 workerPool / `persistProgress`（:700-711）/
     `updateEntity`（:717-726）在池线程跑，直取必得 null。stamping 规则必须区分：
     **username 只在请求线程写**（addDownload/startDownload 等入口，或行上已有值不覆盖）；
     worker 只 bump `lastModified`（state 变更是同步可见字段，不 bump 则 App 增量 pull 永远看不到
     WebUI 发起的下载完成），不写 username。`updateEntity` 同时应跳过 `deleted=true` 的行
     （防 worker 终态保存复活墓碑表象，D9 同源）。

---

## 4. 实施清单

### 4.1 A7-1：统一 stamping（新增 CurrentUsernameProvider + 各写路径补 stamp）

> 约定：新行 `username = provider.currentUsername()`、`lastModified = now`；已有行更新时
> `username` 不覆盖（行上 NULL 才落当前用户），`lastModified = now`。

| 文件 | 方法（行号） | 改动 |
| --- | --- | --- |
| `config/CurrentUsernameProvider.kt`（新增） | — | §3.2 的 Provider bean |
| `FavoriteService.kt` | `addFavorite` :102-140 | 新行 stamp；存在性检查改「活行才算存在」，墓碑行走复活（F3）；构造注入 Provider |
| `HistoryService.kt` | `addHistory` :80-105 | insert 分支 stamp（username+lastModified）；update 分支 bump lastModified（time 已=now）；构造注入 Provider |
| `HistoryService.kt` | `updateFavoriteSlot` :117-122 | favoriteSlot 值实际变化时 bump lastModified（该列是同步可见字段） |
| `GalleryService.kt` | `addToHistory` :589-610 | 与 HistoryService.addHistory 同步修（建议直接改为委托调用 HistoryService.addHistory，消重复）；`getGalleryDetail:423` 调用点随动 |
| `GalleryService.kt` | `createQuickSearch` :656-681 | 新行 stamp（deleted=false） |
| `DownloadService.kt` | `addDownload` :228-253 | 新行 stamp |
| `DownloadService.kt` | `startDownload` :278-282 / `pauseDownload` :313 / `cancelDownload` :325-329 / `moveDownloads` :456-458 / `updateEntity` :717-726 / `persistProgress` :700-711 | 每次行变更 bump lastModified；`updateEntity`/`persistProgress` 跳过 deleted=true 行；worker 不写 username（§3.4） |
| `DownloadService.kt` | `createLabel` :482-492 | 新行 stamp |
| `DownloadUploadService.kt` | `initUpload` :43-87 | 已达标（:80/:83），仅回归确认，勿动语义 |
| `DownloadMaintenanceService.kt` | `clean` :59-96 | 见 A7-2 维护删除 + 遍历过滤墓碑 |

### 4.2 A7-2：软删改造 + clearHistory + deleted 过滤补齐 + adopt 重触发

**删除路径墓碑化：**

| 文件 | 方法（行号） | 改动 |
| --- | --- | --- |
| `FavoriteService.kt` | `removeFavorite` :142-155 | `delete` → `deleted=true; lastModified=now`（username NULL 则落当前用户）；favoriteSlot 回写 -2 逻辑保留 |
| `GalleryService.kt` | `deleteQuickSearch` :683-685 | `deleteById` → 查行后软删（同上） |
| `DownloadService.kt` | `deleteDownload` :335-351、`deleteDownloads` :446-450 | 磁盘文件照删（行为不变），DB 行改软删；`cancelDownload` 状态写不复活（配合 updateEntity 跳墓碑） |
| `DownloadService.kt` | `deleteLabel` :494-498 | 物理删 → 软删 |
| `DownloadMaintenanceService.kt` | `clean` INVALID_DOWNLOADS :87 | 文件照删，DB 行软删 |
| `HistoryService.kt` | `clearHistory` :107-109 | **逐行墓碑化**（§1.4）：当前用户活行 `deleted=true, lastModified=now`，NULL 行就地落 username；单事务 |

**deleted 过滤补齐（§2.2 清单落点）：**

| 文件 | 改动 |
| --- | --- |
| `DownloadInfoRepository.kt` | `SEARCH_WHERE`（:34-42）加 `AND d.deleted = false`（searchDownloads :44 / countSearchDownloads :51 / findAllIdsBy :55 连带生效）；`findByLabel(label, pageable)` :15 / `countByLabel` / `findByState` :17 / `countByState` :19 / `findTitlesByLabel` :71 加 `AndDeletedFalse`；新增分页全量 `findAllByDeletedFalse`（替代 listDownloads :144 的 findAll+count） |
| `DownloadService.kt` | `listDownloads` :144 换过滤查询；`getDownloadInfo` :222-224 跳墓碑；`startAllDownloads`/`pauseAllDownloads`/`restartAllDownloads`/`completeIfVerified` 用过滤后的集合；`listDownloads` :133 labels 过滤 deleted |
| `GalleryService.kt` | `getGalleryDetail` :403/:409/:428 三分支跳墓碑行（download 墓碑 → 落到下一分支）；`getHistory` :613、`getLocalFavorites` :624、`getQuickSearches` :636、`searchLocalHistory` :375/:379、`readProgressOf` :587 补过滤 |
| `LocalFavoriteInfoRepository.kt` / `HistoryInfoRepository.kt` / `QuickSearchRepository.kt` | 按需补 `findAllByDeletedFalse...` 派生查询（列表用）；**不动** `findByUsername*`（pull 载体） |

**adopt 重触发：**

- `SyncService.kt:203-236` 逻辑本身不改。随 A7-1 上线执行一次数据修正：删除 server_config 中
  `sync.ownership.adopted.*` 键（注释 :208 已预留的手工通道），下次任一用户 push/pull 重新收养
  存量 NULL 行并复核置位。stamping 修复后不再有新 NULL 行，短路机制保留作回归防线。

### 4.3 A7-3：属主维度查询（P1-1）

**语义决策（重要）**：现状是「gid 全局单行 + ownedBy 属主保护」，由测试
`user A cannot overwrite user B's existing row`（`SyncServiceTest.kt:1044`）固化（B 已占 gid 时
A 的 push 不插行、不覆盖）。审计要求消除的是**派生查询单实体返回在多行命中时抛
`IncorrectResultSizeDataAccessException`**，不要求切换 per-user 多行模型。

**推荐方案：List 返回 + 属主过滤（保单行语义，回归面最小）：**

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `HistoryInfoRepository.kt:12`、`LocalFavoriteInfoRepository.kt:8`、`DownloadInfoRepository.kt:12`、`BookmarkInfoRepository.kt:8` | `findByGid` 单实体派生查询 | 改/增 `findAllByGid(gid): List<T>`（旧方法在调用方全部迁移后删除） |
| `SyncService.kt` | `mergeFavorite` :256、`mergeHistory` :320、`mergeDownload` :380、`mergeBookmark` :441 | `val rows = repo.findAllByGid(gid); val existing = rows.firstOrNull { it.username == null \|\| it.username == username }`；插入守卫 `raw == null` 改为 `rows.isEmpty()`——他人已占则不插（语义与现状逐字等价，只是不再炸） |
| `HistoryService.addHistory` :82、`GalleryService.addToHistory` :590、`HistoryService.updateFavoriteSlot` :118 | update 路径 | List 化；行属主非当前用户且非 NULL → 跳过更新 + 日志（不跨用户改行；require_auth=false 下全员 "default" 无感知） |
| `FavoriteService.addFavorite` :109 / `removeFavorite` :143、`DownloadService.addDownload` :229 / `completeIfVerified` :408、`DownloadZipImportService` :164、`GalleryService.getGalleryDetail` :403/:409/:428、`readProgressOf` :587、`FavoriteService` :135/:150 | 写前查找/读路径 | 同样 List 化 + `firstOrNull`。**读路径不按属主过滤**（「本地可见性」语义保持，单行模型下 firstOrNull 与原 findByGid 等价），但按 §4.2 过滤墓碑。此区分用测试固化（见 4.4） |

### 4.4 新增测试用例列表

**stamping（A7-1）：**
- HistoryServiceTest：`addHistory insert stamps username and lastModified`；`addHistory update bumps lastModified for incremental pull`；`updateFavoriteSlot bumps lastModified only on change`。
- FavoriteServiceTest：`addFavorite stamps username and lastModified`；`removeFavorite soft-deletes with lastModified bump and resets favoriteSlot`；行仍在库（不再 verify delete）。
- DownloadServiceTest：`addDownload stamps username and lastModified`；`state writes bump lastModified`；`updateEntity skips tombstoned rows`。
- GalleryServiceTest：`addToHistory delegates stamping`（若合并实现则改 verify 委托）；`createQuickSearch stamps`。

**复活语义：**
- FavoriteServiceTest：`addFavorite resurrects a tombstoned row instead of rejecting`（改/补 `duplicate gid is rejected without save` :95——活行仍拒绝，墓碑行走复活）。
- GalleryServiceTest：`deleteQuickSearch soft-deletes`；`getQuickSearches hides tombstones`（Q1）。

**clearHistory（核心验收）：**
- HistoryServiceTest：`clearHistory tombstones rows instead of deleting`（行数不变、deleted=true、lastModified>原值）；`clearHistory scopes to the acting user`；`listHistory empty after clear`。
- 集成（SyncControllerTest 服务层或新集成测试）：`clearHistory tombstones reach an incremental pull`（clear → `pull(since=clear前)` → entities.history 全部 deleted=true）——对应 SyncServiceTest 既有 `history tombstone reaches an incremental pull after the bump`（:919）的 Web 本地删除版本。

**deleted 过滤（A7-2）：**
- DownloadServiceTest：`listDownloads hides tombstones across label q regex and default paths`（total 同步）；`batch all=true resolution excludes tombstones`；`getCompleted and getFailed counts exclude tombstones`；`startAll restartAll pauseAll skip tombstones`；`getDownloadInfo returns null for tombstone`。
- GalleryServiceTest：`getHistory hides tombstones`（H1）；`searchLocalHistory hides tombstones on title and category paths`（H2）；`getGalleryDetail skips tombstoned download history favorite rows in order`（F2/H3/D10）。
- HistoryControllerTest / FavoriteControllerTest：**无需改动**（方案 A 保签名）——本身就是回归用例。

**A7-3：**
- SyncServiceTest：`merge tolerates duplicate gid rows across users without exception`（同 gid 两行 A/B，push 不炸且语义保持）；`user A cannot overwrite user B's existing row`（:1044）必须保持绿。
- HistoryServiceTest：`addHistory skips updating another user's row`（require_auth=true 场景）。
- 读路径固化：`getGalleryDetail still resolves rows regardless of owner`（本地可见性语义回归锚）。

**Provider/认证上下文：**
- CurrentUsernameProvider 单测：context 空 → "default"；置 "alice" → "alice"。
- @WebMvcTest 层：`favorite add under require_auth=false stamps username default`。

---

## 5. 风险与回归面

**必须全绿的既有 SyncService 测试（墓碑/属主/增量三族 + 策略矩阵）：**

- 墓碑族（`SyncServiceTest.kt`）：`favorite tombstone is stored for an unknown gid`(:835)、
  `favorite union keeps an alive row when incoming is a tombstone`(:844)、
  `favorite resurrects a tombstoned row`(:876)、`history delete keeps a tombstone row and bumps
  lastModified`(:885)、`history delete of an unknown gid stores a tombstone`(:897)、
  `re-pushing the same history tombstone stays idempotent`(:907)、
  `history tombstone reaches an incremental pull after the bump`(:919)、
  `full pull returns history tombstones with deleted flag`(:930)、
  `full pull includes records with lastModified zero`(:940)、
  `download tombstone with no local record is stored, not deleted`(:486)、
  `download union keeps an alive row when incoming is a tombstone`(:497)、
  `download resurrects a tombstoned row`(:509)、
  `bookmark delete keeps a tombstone row and bumps lastModified`(:622)、
  `bookmark delete of an unknown gid stores a tombstone`(:634)、
  `bookmark tombstone reaches an incremental pull after the bump`(:643)、
  `quick search tombstone is stored for an unknown name`(:694)、
  `quick search resurrects a tombstoned preset`(:709)、
  `download label tombstone is stored for an unknown label`(:740)、
  `download label resurrects a tombstoned label`(:755)、
  `download label deduplicates on the label key and survives tombstone pushes`(:729)、
  `filter tombstone is stored for an unknown key`(:583)。
- 属主/收养族：`user A cannot overwrite user B's existing row`(:1044)、
  `legacy null-username rows are claimed by the first pushing user`(:1057)、
  `adoptNullOwnership short-circuits after full adoption (P3)`(:1255)、
  `download push of a new gid stores the entity under the pushing user`(:472)。
- 增量族：`incremental pull queries by username and lastModified and never full-scans`(:1133)、
  `incremental pull filters per user in the query`(:1148)、
  `full pull queries by username and includes zero-lastModified rows (since=0)`(:1159)、
  `incremental pull runs the derived query on every entity type`(:1173)。
- `SyncStrategyMatrixTest` row1–row6（A/C/B × soft/tombstone 删除仲裁矩阵）。
- `HistoryModePassthroughTest`（GalleryService ↔ HistoryService 共享 repo 的端到端，构造点需随
  Provider 注入更新）。

**预期行为变化（有意为之，需在 PR 描述里声明）：**
1. `FavoriteServiceTest:95` `duplicate gid is rejected without save`——墓碑行不再拒绝而是复活；
   活行拒绝保持。
2. `/download/list`、`/gallery/history`、`/gallery/local-favorites`、`/gallery/quick-search` 的
   total/内容在存在墓碑时变小——这是修复不是回归。
3. `deleteDownload`/维护清理后行留在库（deleted=true）——DB 行数不再下降；相关 e2e/运维脚本
   若断言行数需同步。
4. adopt 标志重触发后首轮 push/pull 会做一次 7 表 NULL 扫描（一次性开销，可接受）。
5. require_auth=true 下 B 用户在 WebUI 浏览 A 已同步的画廊不再覆盖 A 的历史行（跳过+日志）——
   多用户正确性修复；单用户（default）部署无任何感知。
6. clearHistory 后 `/history/list` 为空但 DB 行还在——备份体积略增（墓碑行留存，§1.4 已述不 GC）。

**主要残余风险与对策：**
- DownloadService worker 与软删的竞态：`deleteDownload` 已 `awaitFinished`（:339），加上
  `updateEntity` 跳墓碑行后，终态保存不会复活墓碑；仍需上述专项测试覆盖。
- 遗漏的写路径：新增任何 REST 写同步表的方法必须走 stamping（建议在 Provider 的 KDoc 里写明
  约定，并在 A7-T 回归卡里加 grep 检查：`Repository.save(` 的非 SyncService 调用点逐一对账——
  本次调查的对账结果即 §4.1 表格）。
- App 侧无风险：本设计 App 零改动（§1.5）；dev-plan 验收项「Web 加收藏→App 可见；Web 删收藏→
  App 同步删除」由墓碑下发链路直接满足。
