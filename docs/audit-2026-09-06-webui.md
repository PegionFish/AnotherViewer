# WebUI 代码审计报告（2026-09-06）

方法：四域并行只读审计（后端安全 / 后端数据与并发 / 前端状态与网络 / 契约与测试）+ 对全部 P0/P1 结论的人工代码复核。未访问运行实例、未发任何内容请求。所有行号以 HEAD `a891f520` 为准。严重度按**本仓库实际部署形态**（个人自用、局域网 192.168.6.141、单账号、App+WebUI 双端同步重度使用）校准。

**威胁模型声明（2026-09-06 用户定案，同日二次限定）**：本工具只考虑单人用户在私有网络 infra 上运行，不开放外部网络访问，安全性做相应放宽——**放宽的是「要不要额外加固」，不是「已实现功能的逻辑可以残缺」**：产品模型里存在的功能（认证/注册/多用户所有权/同步），其代码逻辑必须完整正确，半成品同样按缺陷处理。据此安全类「加固建议」降级，功能完整性问题维持原级。**注意边界**：此放宽仅限网络基础设施安全，**不涉及内容打码模式**——防的「站点风控」是另一个威胁模型，打码开关与脱敏链路维持既有定案不动。

## 总评

架构纪律性整体很高：`/api/**` 统一 bearer 认证、代理类端点全部有 host 白名单（无 SSRF）、push 有事务、合并逻辑 tie-break 无丢失路径、26 个控制器测试全覆盖、契约三方（spec/后端/前端）参数级同步、无 TODO 欠账、无死组件。**最严重的问题集中在一个系统性断裂上：Web 侧本地写入不落同步模型所需的 stamping 字段（username/lastModified/软删墓碑），导致 Web 与 App 的双向同步存在静默单侧失效面。** 其余为局部竞态与性能隐患。

---

## P0（系统性缺陷，建议独立立项）

### P0-1 Web 本地写路径与同步数据模型断裂

**证据链（全部核实）**：
- `FavoriteService.kt:102-140` `addFavorite` 落库不写 `username`（默认 null）、不写 `lastModified`（默认 0）；`HistoryService.kt:89-102`（addHistory insert）、`GalleryService.addToHistory`、`DownloadService.addDownload`、`GalleryService.createQuickSearch/deleteQuickSearch` 同族。
- `SyncService.kt:203-236` `adoptNullOwnership`：NULL 行收养是**一次性迁移**（server_config 标志短路），注释明言「导入/还原均显式落 username，不会再生 NULL 行」——该假设被上述 Web 写路径直接违反。标志置位后新生的 NULL 行**永不被收养**。
- 后果一（不可见）：App `pull` 走 `findByUsername`，Web 新增行 username=null → App 全量/增量拉取**永远看不到** Web 加的收藏/历史/快搜。
- 后果二（复活）：`removeFavorite` 物理删行（收藏在同步模型里是软删实体）→ App 下次 push 同 gid 时 `raw==null → save`（union-merge 重建）→ **Web 删除被静默撤销**；`clearHistory` 的 `deleteAll()` 连墓碑一起物理清。
- 后果三（推送互斥）：App push 撞上已有 NULL 行时 `ownedBy` 判非本用户 → `existing=null, raw!=null → return false` 静默跳过，服务端保留的是那条无主 Web 行，双端数据静默分叉。

**与既有方案的关系**：A3 的次级缺陷（addHistory 不 bump lastModified）只是本断裂的一个子集。**A3 修复应泛化为 A7**（见文末处置建议）。

**修复方向**：统一 stamping 切面——所有 Web 本地写从 SecurityContext 取 `authentication.name` 落 username、`lastModified=now`；收藏/下载等软删实体的 Web 删除改软删（bump lastModified 落墓碑）；`clearHistory` 改为逐行墓碑化或显式同步删除事件；置位过的 adopt 标志按需重触发一次收容存量 NULL 行。**username 一致性已核实（2026-09-06）**：`SyncController` 的 push/pull 均以 `authentication.name` 为同步用户——require_auth=false 时 `AuthTokenFilter` 将全部请求（含 Android）置为 "default" 主体，认证开启时两端同取登录名；因此 stamping 直接用 `authentication.name` 即可，两种模式下与 App 推送天然一致，无需特判。同步路径的 `findByGid` 一并改带属主维度（见 P1-1）。

---

## P1（重要缺陷，当前形态可触发或一开认证即埋雷）

### 后端

**P1-1 `findByGid` 单行查询在多用户同 gid 时抛异常** — `HistoryInfoRepository.kt:14` 等三仓（派生查询返回单实体，命中 2 行抛 `IncorrectResultSizeDataAccessException`）。**定性为功能完整性缺陷（用户 2026-09-06 二次限定后维持 P1）**：同步数据模型实现了多用户所有权（username 列、ownedBy 属主保护、注册端点），但查询层在第二用户出现时即崩——「模型有、逻辑没实现完」。**列入 A7 正式范围**（stamping 改造本就要动这些查询路径，改为 `findByGidAndUsername` / List 返回 + 属主过滤）。

**P1-2 注册开关 = 认证开关** — `SiteAuthService.kt:74` `isRegistrationAllowed() = isAuthEnabled()`。按私有网络模型**不加独立开关**（注册功能本身按此设计连贯地实现了，能注册、能登录、能撤销设备）；其与 P1-1 的组合缺陷（第二用户注册后 push 500）由 A7 修复后即恢复完整——注册功能的端到端正确性经由 A7 兜底，不单独立项。

**P1-3 `/gallery/history`、`/gallery/favorites` 不过滤墓碑 + 全表加载** — `GalleryService.kt:612-633`（`findAllByOrderByTimeDesc` 无 `deleted` 过滤，内存 drop/take 分页）。**联动事实（契约审计）**：这两个端点是零调用死端点（前端走 `/history/list`、`/favorite/list`，Android 也不调）——实际暴露面为零，严重度因此从 P1 降为「随死端点处置」。若 A4 未来复用请先补过滤。

### 前端

**P1-4 useEnhancedImage 跨画廊污染** — `useEnhancedImage.ts:156-160`：`img.onload` 闭包无 gid/代次守卫，`watch(gid)` 的 `resetState()` 清空 map 后，在途预加载完成仍 `enhancedUrls.set(pageIndex, url)`——旧画廊的增强图 URL 写进新画廊的 map，阅读器热替换显示错误图片。修复：闭包捕获发起时 gid，完成时不符则丢弃。

**P1-5 SearchView 全局键监听在 KeepAlive 停用后仍劫持按键** — `SearchView.vue:825/846`（onMounted 添加、仅 onBeforeUnmount 移除；SearchView 在 CACHED_VIEWS 内）。停用实例在任意视图后台响应 `/`、`f/F` 并 preventDefault。修复：移到 onActivated/onDeactivated。

**P1-6 SW 的 API 缓存不随登出清空** — `stores/auth.ts:33-39`（logout 无缓存清理）+ `sw.js:136-139`（/api/ GET 缓存 30min，键=URL 无用户维度）。单账号私有部署下跨账号场景不存在，**降为 P2-正确性**：仅在未来多账号（或实例转公开）时显形；登出清缓存作为低成本顺手项，不单独立项。

---

## P2（择要：竞态 / 性能 / 契约 / 部署）

**后端竞态与一致性**
- `startDownload` 双提交竞态可双 worker 写同文件（`DownloadService.kt:259-304`；修复 `putIfAbsent` 占位）。
- cancel 等 90s 超时后 worker 终态覆盖「已取消」（`:317-333` vs `:630-636`；终态写入前重读行）。
- `DownloadDirIndex.refresh` 非原子 swap，并发读见空索引（`DownloadDirIndex.kt:114-126`；改整体引用替换）。
- Backup 还原后未重启期间的写入落进已改名旧库丢失（`BackupService.kt:274-303`；restorePending 时设写闸门）。
- pull 无事务快照且水位线先取，并发 push 下增量永久漏行（`SyncService.kt:109-154`；readOnly 事务 + 查询后取水位）。
- 超大 push 单事务（8797 行 × findByGid+save+provenance KV 双写）在 SQLite 单写者下可阻塞全部 Web 写 30s+（`:71-107,749-751`；provenance 改实体列/分批提交）。
- `applySyncEhSession` 在 push 事务内做不可回滚的内存/KV 副作用（`SiteSessionManager.kt:225-265`；挪 afterCommit）。
- `addFavorite/removeFavorite` 跨 3 表写无 @Transactional（`FavoriteService.kt:102-155`）。

**后端性能**
- HistoryService q/regex 与 FavoriteService 分页均为全表载入内存过滤（`HistoryService.kt:42-67`、`FavoriteService.kt:39-67`）；下沉 DB 派生查询 + Pageable。
- `searchCache` 无 maximumSize（`GalleryService.kt:63-65`；补 128 对齐 feedCache）。
- ImageCache 逼近 10GB 上限后每次写盘触发全树扫描排序（`ImageCacheService.kt:288-306`；驱逐节流 + LRU 队列）。
- `pruneOrphanProvenance` 每日 7×findAll 全表遍历（`SyncService.kt:777-783`）。

**前端**
- 路由守卫对免认证部署的匿名会话每次导航都发 `/auth/status`（`router/index.ts:97-109`；模块级缓存）。
- DownloadView 被 KeepAlive 停用后 STOMP 连接整会话常驻（`DownloadView.vue:927-978`；注释表明缓存保鲜有意，连接常驻是隐含成本——评估 deactivation 时断连）。
- SearchView 的 loadingMore 复位是「恰好不炸」结构（`SearchView.vue:536-601`；对齐 HomeView F5 无条件复位）。
- preferences store 保存失败后 dirty 永真，本会话所有后续 load 被丢弃（`stores/preferences.ts:23,55-68`）。
- 对话框 Escape 监听与 400ms 搜索防抖在 KeepAlive 停用/卸载后残留（HistoryView/DownloadView/HomeView/FavoriteView 各一处）。
- SW 对跨域 opaque 图片永远判不新鲜，在线时等效 NetworkFirst + 双写缓存（`sw.js:244-257`）。
- 401 拦截器硬跳 `/login` 丢深链、在途请求不取消（`api/client.ts:82-86`；改 router.replace + redirect query）。

**安全（按私有网络威胁模型全部为「记录不改」）**
- `/api/v1/health`、`/api/v1/metrics/**` permitAll（`SecurityConfig.kt:79`）——运维端点无认证；私有网络可接受。
- require_auth 默认 false（`AuthTokenFilter.kt:24`）——LAN 部署的既定常态。
- token 存 localStorage（XSS 面）+ CSP `script-src 'self'` 收窄 + 无 cookie 免 CSRF——自托管可接受取舍。
- 注册开关 = 认证开关（原 P1-2 重标）——LAN 内可接受，不做独立开关。

**契约与文档**
- spec 缺失的已上线端点：`POST /download/restart-all`、`GET/POST /privacy/mask`、`POST /site/proxy`（openapi.yaml 均无）。
- 死端点：`POST /download/import-zip`（三重孤儿：后端有/spec 无/客户端无）、`GET /gallery/history|favorites`（见 P1-3）、Archive/Torrent/Process/Cache 四组控制器 spec+实现+测试齐全但零消费方。
- 文档：仓库 URL 两处口径不一（service 单元 vs README/deployment）；双 systemd 单元并存（packaging tpl vs deploy/）未互引。

---

## 已核实无问题（正面结论，供后续审计免重查）

- SSRF：SiteProxy/ImageProxy 均 host 白名单（`SiteProxyController.kt:63-67`、`ImageProxyController.kt:121`）。
- WS：STOMP CONNECT 帧鉴权（require_auth 开时），连接计数经 session 生命周期事件无泄漏；CSRF 关闭配 STATELESS bearer 是正确组合。
- 登录限流 (user,ip) 分桶 + 定时清理；XFF 伪造被 RemoteIpValve 内部网段信任策略挡住。
- 打码过滤器路径门控与 redact 规则锚点设计无误伤面；token/readProgress 不在脱敏字段集。
- push 有事务、合并 tie-break 无丢数据路径、`page` 取 max 唯一落点、墓碑/复活符合协议、ownedBy 属主保护（findByGid 不爆炸前提下）。
- JobService/JobStore：ConcurrentHashMap + putIfAbsent、终态 TTL 清理、无无界增长。
- 缓存键完整（searchCache key 含全部筛选参数）；「feed/search 缓存富化旧值」的注释担忧在实际代码中不存在（enrich 只填空字段）。
- Backup 导出 VACUUM INTO 快照 + 归档路径穿越双重防护。
- SW：POST/Range 不缓存；更新流程无 reload 死循环；token 在 localStorage 但守卫直读无竞态。
- 前端竞态守卫：Favorite/History/Search/GalleryDetail 均有 requestSeq 三处齐全；ReaderView 计时器/监听成对清理；availability 单飞。
- DDL：`page` 列已带 default（生产踩坑后修复）；`mode/deleted/lastModified` 为裸 NOT NULL 但均已在线、仅在「对已填充表补列」场景才会踩 SQLite 坑——存量部署无行动项，新列一律带 default 即可。
- 测试：26 控制器全覆盖；无 TODO/FIXME 欠账；前端唯一零测试视图 AdminAbout.vue；版本号三方一致。

---

## 处置建议

1. **A7（新，P0-1）**：Web 本地写统一 stamping + 软删改造——**吸收原 A3 的 lastModified bump 子项**，A3 缩回纯前端修复（resolveStartPage/watcher/mock）。A7 涉及 7 张同步表的写路径与认证上下文，建议单独成文实施；`findByGid → findByGidAndUsername`（原 P1-1）作为纯健壮性改造随 A7 顺带。
2. **并入既有波次**：P1-4/P1-5（reader/search 域小修）随 W1；P2 前端项随 A4 的列表域改造顺手处理；P2 后端性能项（全表过滤、searchCache 上限）可与 A2 的后端分页恢复同批。
3. **契约卫生（低优先，随手做）**：spec 补 restart-all/privacy/site-proxy POST；死端点清理（import-zip 删除、四组零消费控制器去留决策）。
4. **记录不改（按 2026-09-06 威胁模型定案）**：require_auth 默认 false、metrics/health 裸奔、localStorage token、注册开关与认证共用——私有网络单用户场景全部可接受；SW 登出清缓存（原 P1-6）降为顺手项。**若未来转公开部署，此清单即待办清单。**
