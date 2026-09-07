# 图像处理管线集成（EntryPoint）执行交付手册

> 日期：2026-09-08 ｜ 分支：BiLi_PC_Gamer ｜ 前置：settings 去重批已部署（12422791）
> 执行方式：**多子代理并行**——协议见 §9，任务卡见 §10，歧义一律查 §11 默认决策表，查不到的回报主模型裁决，**不得自行发明**。
> 对接目标：EntryPoint 本地推理平台（「副武器」），部署于 **141:9800**，与 AnotherViewer Web 同机。

---

## 0. 目标与非目标

**目标**
1. 打通 AnotherViewer → EntryPoint 的图像处理链路：现有 `ImageProcessor` SPI 的第一个真实实现（目前只有 Noop 占位）。
2. 自动化：**下载完成后自动**对下载完的画廊跑处理管线；**定期调度**扫描下载库补跑未处理页。
3. 记忆：处理任务/页级历史**落 SQLite**（重启不丢），含时间戳、触发来源、源文件与产物地址、机读错误码+文案。
4. 监控：`settings/server/processing` 页新增「进行中任务」（时间戳/进度/文件源/存储地址/报错）与「历史记录」两个区块，报错可见、可重试/取消。

**非目标**
- 不做 Android 端消费（contracts 更新后 App 未来可接）；不做用户过滤范围（scope=全部下载库，个人自用）；不做同机免上传直读（EntryPoint 三期特性，见 §8 风险）；不做 EntryPoint 管线 DAG 编排（单能力提交自行编排，符合对接指南 §7 隔离原则）。

---

## 1. 已实测的对接契约（141:9800 部署版，2026-09-08 冒烟）

权威文档：`/home/bob/EntryPoint/docs/API_INTEGRATION_GUIDE.md`（中立五操作模型）+ `INFERENCE_API.md`（字段级）。**以下为部署版实测，与文档的差异以实测为准**：

| 操作 | 端点 | 实测 |
|---|---|---|
| 能力发现 | `GET /api/v1/capabilities` | ✅ 需 token；返回 `{"capabilities":[{module_id, capability, input_type, output_type, max_file_size_mb, params:{name:{type,default,min,max,...}}}]}`（每个 module×capability 一条，**扁平结构，无 modules 嵌套**） |
| 提交 | `POST /api/v1/inference/{module_id}/{capability}` | ✅ multipart `file`（+可选 `params` JSON 字符串）→ `{"queue_position":1,"task_id":"task-20260907-171133-0005"}` |
| 轮询 | `GET /api/v1/inference/result/{task_id}` | ✅ `{"task_id","status":"completed","outputs":[{node_id,url}...]}`；outputs 含 `input`/`run`/`output` 三节点，取 `node_id=="output"` |
| 产物下载 | `GET /api/tasks/{task_id}/artifacts/output` | ✅ **302 → 文件流**（HTTP 客户端必须跟随重定向），不在 `/api/v1` 下、**不受 token 保护** |
| 取消 | `POST /api/v1/tasks/{task_id}/cancel` | ⚠️ 部署版未验证（`/api/v1/tasks/*` 路径整体不存在，见下） |
| 任务列表 | `GET /api/v1/tasks` | ❌ **部署版不存在**（返回 `{"error":"接口不存在"}` 中文兜底） |
| 单任务查询 | `GET /api/v1/tasks/{task_id}` | ❌ 同上——**必须用 `result` 端点轮询** |

**鉴权**：`Authorization: Bearer <token>` 或 `X-API-Key`。token 在 141 的 `/server/EntryPoint/config/app.toml` `[api] token`（**严禁写入本仓库与本文档**；部署阶段人工经设置 UI 写入，服务端加密存储）。

**部署版能力目录（图像相关）**：

| module | capability | 输入→输出 | max | 参数 |
|---|---|---|---|---|
| rembg | remove_bg | image→image | 50MB | alpha_matting, post_process |
| birefnet | matte | image→image | 100MB | model |
| realesr | upscale | **video**→video | 4096MB | scale_factor, target_preset, tile_size |
| animevideo | upscale | video→video | 4096MB | scale_factor, tile_size |
| comfyui-bridge | generate | file→file | - | workflow, base_url, ...（无已部署 ComfyUI，不用） |

> 冒烟记录：64×64 PNG → rembg/remove_bg → 约 5s 终态 completed → 产物为 RGBA PNG（302 跟随后 200 image/png）。realesr 对图像输入的 multipart 提交 60s 无响应（视频模块冷启动/不接受图像，未定论）——见 §8 风险 R1。

**错误信封**（v1 稳定）：`{"error":{"code":"MACHINE_CODE","message":"人类可读"}}`。机读码表见指南 §8（`UNAUTHORIZED/PARAM_INVALID/INPUT_INVALID/MODULE_NOT_FOUND/CAPABILITY_NOT_FOUND/MODEL_NOT_READY/MODULE_START_FAILED/QUEUE_FULL/TASK_NOT_FOUND/INTERNAL`）。**客户端逻辑只允许 switch code，禁止解析 message**。

**可移植性硬约束**（合并前对照指南 §9 清单自查）：base/token/超时全配置注入；状态归一化词表（queued/running/completed/failed/cancelled → 内部 TaskState）；产物 URL 只当不透明句柄（base+url GET，禁止解析）；429/5xx 指数退避；整体业务超时到点先 cancel 再标失败。

---

## 2. 架构设计

```
[触发源]                          [编排]                          [适配]                       [EntryPoint 141:9800]
下载完成事件 ──┐                                                    ┌─ EntryPointClient ─ upload→submit→poll→download
(DownloadProgress   ImageProcessingService（改造）                     │  (OkHttp，中立五操作，
 state=3 @EventListener)   ├─ submitGallery：页级去重(D8)             │   错误信封→机读码，状态归一化)
定时扫描 ──────┘ @Scheduled ├─ 逐页调 processor.process(input) ───────→ EntryPointProcessor
手动 POST /process/gallery ─┘   (Semaphore 并发闸，沿用)              │  (upload 引用→submit→poll
                                ├─ ProcessingEvent×4 → WS /topic/process/*（契约不变）
                                └─ 写穿 → ProcessingTaskStore ──→ SQLite processing_task 表（新）
                                     ↓ 查询
                                ProcessingController 新端点 ─→ 前端 AdminProcessing.vue 三区块（3s 轮询）
```

设计要点：
- **内存为主、DB 写穿**：`ImageProcessingService` 现有内存 map 与 Semaphore 调度**保持不变**，每个状态迁移同步写穿 `ProcessingTaskStore`（新增）；历史查询走 DB，活跃查询走内存（含未落库的瞬时态）。
- **触发源零侵入**：下载完成用 `@EventListener(DownloadProgress)`（`state==3` 时触发），不改 `DownloadService`；定时用 `@Scheduled` 心跳（复用现有 `@EnableScheduling`）。
- **EntryPoint 细节全部隔离在 `processing/ep/` 包**（client + processor + mapper），核心服务只依赖 `ImageProcessor` SPI 与归一化词表——换供应商 = 换包内两个类。

---

## 3. 数据模型（新表 `processing_task`）

实体 `web/entity/ProcessingTaskEntity.kt`，Hibernate `ddl-auto: update` 自动建表；**NOT NULL 列必须带 columnDefinition default**（SQLite 加列限制，失败仅 WARN）：

| 列 | 类型/DDL | 说明 |
|---|---|---|
| id | `@Id @GeneratedValue(IDENTITY) Long` | |
| task_id | `VARCHAR(64) NOT NULL` UNIQUE + idx | `proc-xxxxxxxx`（沿用现有生成规则） |
| gallery_id | `INTEGER NOT NULL default 0` + idx | |
| title | `VARCHAR(256) NOT NULL default ''` | 提交时快照（列表展示免联查） |
| trigger | `VARCHAR(16) NOT NULL default 'MANUAL'` | MANUAL / DOWNLOAD_AUTO / SCHEDULED |
| processing_type | `VARCHAR(24) NOT NULL default ''` | ProcessingType.name() |
| state | `VARCHAR(16) NOT NULL default 'QUEUED'` + idx | QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED（复用现有 TaskState） |
| pages_total / pages_done / pages_failed | `INTEGER NOT NULL default 0` | 页级进度 |
| source_dir | `VARCHAR(512) NOT NULL default ''` | 输入页所在目录（下载目录或缓存目录） |
| output_dir | `VARCHAR(512) NOT NULL default ''` | enhanced 产物目录 |
| ep_task_ids | `TEXT` | EntryPoint 侧 task_id 逗号串（调试用，可空） |
| error_code | `VARCHAR(64) NOT NULL default ''` | 机读码（EntryPoint code 或 EP_UNREACHABLE/EP_TIMEOUT/EP_INTERRUPTED） |
| error_message | `VARCHAR(2048) NOT NULL default ''` | 截断 2KB |
| created_at / started_at / finished_at / updated_at | `BIGINT NOT NULL default 0` | epoch millis（库惯例，非 Instant） |

索引：`idx_processing_task_state (state, created_at)`、`idx_processing_task_gallery (gallery_id, processing_type)`。建表索引生效性照 `SqliteIndexDdlTest.kt` 写 DDL 测试。**页级去重不走唯一约束**（重跑会产生多行），由提交时查询过滤（D8）。

Repository：`web/repository/ProcessingTaskRepository.kt`——`findByTaskId`、`findByStateInOrderByCreatedAtDesc`、`findByGalleryIdAndProcessingTypeAndState`、分页 `findByStateNotIn(..., Pageable)`。

---

## 4. 契约变更

**REST 新端点**（挂现有 `/api/v1/process` 前缀，进 `contracts/openapi.yaml`）：
- `GET /api/v1/process/tasks?active=1` → `{"tasks":[ProcessingTaskRecord]}`——active=1 只回内存活跃（QUEUED/RUNNING，含瞬时进度），缺省回 DB 最近 200 条终态+活跃混合列表。`ProcessingTaskRecord` = §3 表字段的 JSON 镜像（camelCase）。
- `GET /api/v1/process/history?page=0&size=50&state=` → 分页（size 上限 500，默认 50），`{"items":[...],"total":n,"page":p,"size":s}`。
- `POST /api/v1/process/retry/{taskId}` → 复制原任务参数重新 submitGallery（仅 FAILED/CANCELLED 可重试；去重豁免——只重跑原任务失败页），返回新 ProcessingTaskResponse。
- 取消沿用现有 `POST /api/v1/process/cancel/{taskId}`（本地取消；EntryPoint 侧按 D3 降级语义尽力而为）。

**WS 不变**：`/topic/process/{taskId}`、`/topic/process/all`、`image.enhanced.ready` 契约与现有 `ProcessingEventHandler` 原样复用；自动/定时触发与手动触发的任务发布同一事件面（**不改 websocket-protocol.md**）。

**Settings 契约**：`ProcessingSettings` 组扩展（现有 4 键不动）：

| 键（serverConfig） | Settings JSON 字段 | 类型/默认 | UI |
|---|---|---|---|
| processing.entrypoint.url | entrypointUrl | String，默认 `http://192.168.6.141:9800` | AppTextField |
| processing.entrypoint.token | （写入专用） | String，`enc:v1:` 加密落盘 | password 输入；GET 只回 `entrypointTokenSet:Boolean`（仿 proxyPassword 惯例） |
| processing.automation.enabled | automationEnabled | Bool，默认 false | AppSwitch「下载完成后自动处理」 |
| processing.automation.periodic.enabled | periodicEnabled | Bool，默认 false | AppSwitch「定期补跑未处理页」 |
| processing.automation.periodic.interval_minutes | periodicIntervalMinutes | Int，默认 60，min 15 | AppSelect（15/30/60/360/1440） |
| processing.type_mapping | （不进 Settings DTO） | JSON，见 D5 | 不做 UI（serverConfig 手改/后续增量） |

接线四处：`ServerConfigService.KEY_*`+defaults → `SettingsDto.kt`（ProcessingSettings/ProcessingSettingsUpdate + entrypointTokenSet）→ `SettingsService` 双写（token 经 EncryptionService；PUT 缺省 token 字段=保留旧值）→ openapi.yaml schema。`SiteDataDirInitializer` 回喂不需要（这些键运行时即时读取，不走 SiteCoreConfigProperties）。

**ProcessingType 扩展**：新增 `REMOVE_BG`（`web/processing/ImageProcessor.kt` 枚举 + openapi enum + 前端类型选项）。

---

## 5. 自动化设计

1. **下载完成触发**：`@EventListener` 收到 `DownloadProgress(state=3)` → 条件闸：`processing.enabled && automationEnabled && onDownloadComplete(并入 automationEnabled，不单设键)` && 默认类型已映射 → `submitGallery(gid, 全页, 默认类型, trigger=DOWNLOAD_AUTO)`。同 gallery 已有活跃任务 → 跳过并 INFO 日志；全部页已处理 → 零提交（不产生空任务行）。worker 线程上下文——**不得触碰 SecurityContext**（A7）。
2. **定期补跑**：`@Scheduled(fixedDelayString = "${anotherviewer.processing.automation.scan-interval-ms:60000}")` 心跳每轮：读 periodic 开关；距上次触发 ≥ interval_minutes 才执行——扫下载库（`DownloadService` state=3 存活行）→ 逐 gallery 计算未处理页（D8 去重查询）→ 有缺口则 submitGallery(trigger=SCHEDULED)。上次触发时间存 serverConfig（`processing.automation.last_scan`，无 UI）。
3. **页级去重（D8）**：提交前按 `(gallery_id, processing_type)` 查 DB 中 COMPLETED 页集合 + 检查 enhanced 输出文件存在性，二者任一命中即跳过该页；`force=true`（手动重试路径）豁免。
4. **启动对账**：`@PostConstruct` 把 DB 中 QUEUED/RUNNING 行置 `FAILED / EP_INTERRUPTED / "服务重启中断"`（EntryPoint 重启同样清空其任务注册表，无从续查——诚实标失败，补跑交给定时器）。
5. **失败重试**：UI 重试按钮 → `POST /process/retry/{taskId}`；定时补跑天然兜底（失败页下次扫描重试，无自动退避——QUEUE_FULL 等由 client 内退避承担）。

---

## 6. 前端设计（`AdminProcessing.vue` 单页三段，版式沿用 .processing__column）

1. **处理设置**（现有 4 行保留）+ 新增自动化 PrefRows：EntryPoint 地址（AppTextField）、token（password 输入，占位显示「已配置/未配置」，留空提交=保留）、下载完成后自动处理（AppSwitch）、定期补跑（AppSwitch + 间隔 AppSelect）。写入沿用**完整段 600ms 防抖 PUT + 失败回滚 + snackbar + unmount 冲刷**惯例。
2. **进行中任务**（新 PrefCard）：3s `setInterval` 轮询 `GET /process/tasks?active=1`（挂载启动、unmount 清理；SmbBackupView 纯轮询先例，不引 WS）。行内容：任务号+画廊标题、类型+触发来源徽标、进度（pages_done/total + 当前页）、开始时间戳与已耗时、source_dir → output_dir、错误行（error_code+message，红色）。行内按钮：取消（RUNNING 时）。
3. **历史记录**（新 PrefCard）：`usePagedList` 分页（50/页）消费 `/process/history`，状态过滤 AppSelect（全部/完成/失败/取消）；行展开显示完整错误与 ep_task_ids；FAILED 行「重试」按钮。
4. 复用组件：`PrefCard/PrefRow/AppSwitch/AppSelect/AppTextField/SectionHeader`（`@/components/form` 只读契约）；**不需要**新 store、不改 KeepAlive 名单、无 i18n（硬编码中文）。

---

## 7. 测试与验证计划

| 层 | 内容 | 命令/方式 |
|---|---|---|
| client 单测 | MockWebServer（**需在 anotherviewer-web/build.gradle.kts 加 `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`**）：提交形状/信封解析/归一化/429-5xx 退避/302 产物下载/token 头 | `./gradlew :anotherviewer-web:test --tests "*EntryPoint*"` |
| processor/service 单测 | fake client 注入；上传→提交→轮询→下载临时文件；超时→cancel→FAILED(TIMEOUT)；写穿/去重/对账/触发条件 | `--tests "*Processing*" "*Automation*"` |
| controller 测试 | standalone MockMvc：新端点 200/404/409/校验错误信封 | `--tests "*ProcessingController*"` |
| DDL 测试 | 新表索引落库（SqliteIndexDdlTest 模式） | `--tests "*Sqlite*"` |
| 前端单测 | api/processing.spec；AdminProcessing.spec 扩展（新控件持久化断言 + 活跃/历史列表渲染 + 重试/取消调用） | `npm test && npm run typecheck` |
| 视觉回归 | admin-processing 版式变更 → 重录 12 张基线 + compare（阈值 1%，actual 全量） | `npm run test:visual:update && npm run test:visual` |
| 集成冒烟 | `scripts/entrypoint-smoke.sh`（读 `EP_TOKEN` 环境变量，**脚本与仓库不得硬编码 token**）：capabilities → 上传合成 PNG → remove_bg 提交/轮询/下载校验 PNG 签名 | 部署后在 141 本机执行 |
| 实弹验证 | 设置 UI 写 URL+token → 对真实下载画廊提交 1 页 REMOVE_BG → 监控页观察进度/历史行/错误面 | 浏览器（138 行惯例：强刷） |

---

## 8. 风险与开放项

- **R1 图像超分缺位**：部署版无 image→image 超分（realesr 声明 video，冒烟未返回）。默认映射仍指向 realesr（实验性），**REMOVE_BG 为已验证的默认可用能力**；超分真实化是 EntryPoint 侧增量（waifu2x/realesr image 模块），不在本项目内。
- **R2 模块冷启动**：首次提交触发模块自动拉起，可达分钟级（uv 环境/模型）。缓解：submit HTTP 超时放宽 120s + 异步轮询；监控页排队态可见。
- **R3 双侧重启清内存**：EntryPoint 任务注册表不持久（其文档 §10.2）；我方对账标 EP_INTERRUPTED，补跑交给定时器。
- **R4 吞吐**：模块内单 worker 串行 + 我方 Semaphore(1)，200 页画廊 ≈ 小时级。去重续跑 + 监控可见即为设计应对，不再加速。
- **R5 部署版与文档漂移**：`/api/v1/tasks`、cancel 已漂移（§1）。集成卡（C9）必须先跑 `scripts/entrypoint-smoke.sh` 再实弹；cancel 端点 404/中文兜底一律按「已终结」吞掉。

---

## 9. 多子代理并行开发协议

**波次结构**（合同先行、文件域互斥、波门全量绿后放行）：

```
W0 主模型串行：C0 契约冻结 commit（所有跨代理接口一次定稿）
W1 三代理并行：C1 后端-适配域 ┊ C2 后端-持久化域 ┊ C3 前端-API域   （文件互斥，见矩阵）
W2 三代理并行：C4 后端-编排域 ┊ C5 后端-端点域 ┊ C6 前端-页面域
W3 主模型+代理：C7 全量门 → C8 视觉基线+文档
W4 主模型串行：C9 打包部署 141 + 冒烟 → C10 实弹验证 + 收尾 commit
```

**文件所有权矩阵**（卡外文件只读；需要改 → 回报主模型，不得静默改）：

| 代理 | 独占写文件 |
|---|---|
| C1 | `web/processing/ep/**`（EntryPointClient/DTO/EntryPointProcessor/CapabilityMapper + 测试）、build.gradle.kts 的 mockwebserver 行（W0 已加则只读） |
| C2 | `web/entity/ProcessingTaskEntity.kt`、`web/repository/ProcessingTaskRepository.kt`、`web/processing/ProcessingTaskStore.kt` + 对应测试 |
| C3 | `web-frontend/src/api/processing.ts`、`src/api/__tests__/processing.spec.ts` |
| C4 | `web/processing/ImageProcessingService.kt`（改造）、`web/processing/ProcessingAutomation.kt`（新：listener+scheduler）、`web/processing/ImageResolver 扩展点`（resolveInputImage 加 DownloadDirIndex 的改动落在 C4 域内）+ 测试 |
| C5 | `web/api/ProcessingController.kt`、`web/dto/ProcessingDto.kt`（新增 Record/History DTO，**不改现有三个 DTO 的字段**）、`contracts/openapi.yaml`、controller 测试 |
| C6 | `web-frontend/src/views/admin/AdminProcessing.vue`、`src/views/__tests__/AdminProcessing.spec.ts`、`src/api/settings.ts`（ProcessingSettings 接口扩展） |

**协作规则**：
1. **合同先行**：C0 落接口桩（client 接口/DTO/store 接口/settings 类型/枚举/依赖行）并 commit；W1 起所有代理只依赖桩编译，接口不满足需求 → 回报主模型改桩+小版本 bump，禁止代理私改共享文件。
2. **共享契约文件锁定**：`openapi.yaml`、`SettingsDto.kt`、`SettingsService.kt`、`ServerConfigService.kt`、`settingsSections.ts`、`contracts/websocket-protocol.md` 在 W0 定稿，后续波次只读（SettingsService/ServerConfigService 的 settings 接线由 **C0 一并完成**，因其面小且稳定）。
3. **单工作树**：禁止代理开分支/worktree/merge 花活；各代理只写自己的白名单文件。**commit 由主模型统一执行**（`git add <白名单文件>` 指定路径），避免 index 竞争；代理完成即回报：done + 触碰文件清单 + scoped 测试输出结论 + 偏离决策表的记录。
4. **波门**：每波末主模型跑全量（后端 `./gradlew :anotherviewer-web:test`、前端 `npm test && npm run typecheck`），绿 → 统一 commit → 放行下一波；红 → 指派原代理修复，不带红进波。
5. **代理并行度**：主模型应一次性并行派发同波全部代理（单消息多 Agent 调用）；代理返回慢不阻塞其他波内代理，波门以全部完成为准。
6. **上下文自足**：派发 prompt 必须自带：本手册路径、该卡全文、白名单文件、验收命令、决策表相关条目；代理不读全手册也能开工。

---

## 10. 任务卡

> 每卡格式：目标 / 白名单 / 依赖 / 关键步骤 / 验收 / commit 信息（主模型执行 commit 时使用）。

### C0 契约冻结（主模型，W0）
- 白名单：`web/processing/ep/`（接口桩+DTO 桩）、`ImageProcessor.kt`（加 REMOVE_BG）、`ProcessingTaskEntity/Repository/Store`（桩）、`ServerConfigService`（KEY_*+defaults）、`SettingsDto/SettingsService`（settings 接线，token 加密+entrypointTokenSet）、`ProcessingDto.kt`（新 DTO 骨架）、`openapi.yaml`、`web-frontend/src/api/settings.ts` + `api/processing.ts`（类型+空实现）、`anotherviewer-web/build.gradle.kts`（mockwebserver）、`application.yml`（scan-interval-ms 键）。
- 验收：全量编译 + 现有测试全绿（桩不破坏现状）。
- commit：`feat(web): 图像处理×EntryPoint 契约冻结——REMOVE_BG 类型、EntryPointClient 接口、任务历史表桩、settings 自动化键接线、openapi`

### C1 EntryPoint 适配域（代理 A，W1）
- 目标：`processing/ep/EntryPointClient.kt`（五操作：capabilities/upload/submit/result/cancel；OkHttp 照 `EhAvailabilityService.probeClient()` 模板 + WebProxyManager selector/authenticator；302 跟随；信封→code；归一化；1s×1.5→5s 轮询；429/5xx 退避）、`EntryPointProcessor.kt`（实现 SPI：process(input)=upload→submit→poll→下载到临时→返回 Path；声明 capabilities=映射非空集合）、`CapabilityMapper.kt`（type_mapping JSON 解析 + capabilities 缓存 + MODULE/CAPABILITY_NOT_FOUND 刷新重试一次）。
- 验收：MockWebServer 全链单测（含 302 产物、401/429/500 分支、超时取消路径）。

### C2 任务持久化域（代理 B，W1）
- 目标：实体/仓库按 §3；`ProcessingTaskStore.kt`：写穿 API（upsert 状态迁移）、活跃/历史/去重查询、`markInterruptedOnStartup()` 对账、DDL 测试。
- 验收：`--tests "*ProcessingTask*" "*Sqlite*"` 绿。

### C3 前端 API 域（代理 C，W1）
- 目标：`api/processing.ts` 完整实现（tasks/history/retry/cancel + Record 类型镜像 openapi）+ 单测（mock client）。
- 验收：`npx vitest run src/api/__tests__/processing.spec.ts`。

### C4 编排与自动化域（代理 D，W2；依赖 C1+C2）
- 目标：`ImageProcessingService` 改造——submitGallery 注入 trigger/去重/写穿/事件不变；processor 路由到 EntryPointProcessor（可用性探测 `nonNoopProcessorAvailable` 语义保持）；`resolveInputImage` 扩展下载目录（`DownloadDirIndex.findPage` 优先，缓存目录兜底）；`ProcessingAutomation.kt`（DownloadProgress listener + @Scheduled 心跳 + last_scan 键）；启动对账挂 @PostConstruct。
- 验收：service 单测（fake processor+store：触发条件矩阵/去重/对账/进度事件）。

### C5 端点与契约域（代理 E，W2；依赖 C0 桩）
- 目标：`ProcessingController` 增 tasks/history/retry 三端点 + `ProcessingTaskRecord/HistoryPage` DTO 补全 + standalone MockMvc 测试 + openapi.yaml paths/schemas 终稿。
- 验收：`--tests "*ProcessingController*"` 绿。

### C6 前端页面域（代理 F，W2；依赖 C3）
- 目标：AdminProcessing.vue 三段（§6）+ spec 扩展（新键持久化断言、活跃列表 3s 轮询 fake timers、历史分页、重试/取消调用、错误展开）。
- 验收：`npx vitest run src/views/__tests__/AdminProcessing.spec.ts`。

### C7 全量门（主模型，W3）
- 后端 + 前端全量绿；红单指派修复。

### C8 视觉与文档（代理或主模型，W3）
- `npm run test:visual:update` 重录 admin-processing 12 张 → compare 通过；`docs/exec-log-2026-09-08.md` 补录本批（沿用 0906 格式）。
- commit：`test(web): 刷新 admin-processing 视觉基线（处理监控三区块）+ docs 补录`

### C9 部署与冒烟（主模型，W4）
- `./build.sh` → scp → 备份 `app.jar.bak-20260908` → 原子替换 → restart → health；141 本机跑 `scripts/entrypoint-smoke.sh`（token 从 141 app.toml 现场读取注入环境变量）。
- 部署后经设置 UI 写入 EntryPoint URL + token（加密落盘）。

### C10 实弹验证与收尾（主模型，W4）
- 真实画廊 1 页 REMOVE_BG 手动提交 → 监控页活跃→历史流转、source/output 地址正确、产物经阅读器 `?enhanced=1` 可见；触发一次「下载完成→自动处理」（小画廊）与定时开关自检。
- 终 commit（如有热修）；回报用户双端验收，**不 push**（惯例）。

---

## 11. 默认决策表（代理免裁决直接执行）

| # | 决策 |
|---|---|
| D1 | token 加密存储（EncryptionService `enc:v1:`），GET 永不回明文，只回 `entrypointTokenSet`；PUT 省略字段=保留 |
| D2 | 只走中立面五操作；wait/callback/WS/pipelines 扩展一律不用；同机免上传不做 |
| D3 | 轮询用 `GET /api/v1/inference/result/{id}`；cancel 走 v1 路径、404/中文兜底/409 一律视为已终结 |
| D4 | 状态归一化：EntryPoint 五态 ↔ 内部 TaskState 原名映射；未知状态按 RUNNING 处理并 WARN |
| D5 | type_mapping 默认：UPSCALE_2X→realesr/upscale{scale_factor:2}；UPSCALE_4X→realesr/upscale{scale_factor:4}；DENOISE/DENOISE_UPSCALE→无映射；REMOVE_BG→rembg/remove_bg{}。无映射类型对处理器不可见 |
| D6 | 新增 `ProcessingType.REMOVE_BG`（web-only + openapi + 前端选项） |
| D7 | 自动触发条件 = processing.enabled && automation.enabled；默认类型取 `processing.default_type`，未映射则不触发并 INFO |
| D8 | 页级去重 = 历史 COMPLETED 行 ∪ enhanced 文件已存在；retry 豁免（只重跑原失败页） |
| D9 | 调度心跳 yml `scan-interval-ms:60000`；到期间隔由 serverConfig `periodic_interval_minutes` 控制（默认 60，min 15） |
| D10 | 内存 map 仍是运行时主拷贝；写穿失败只 WARN 不阻断处理（DB 落后可由历史端点诚实显示） |
| D11 | WS 契约不动；监控页 3s 纯轮询，不引 WS 订阅 |
| D12 | error_message 截断 2048 字符；message 不做机判 |
| D13 | 历史不分页上限：size≤500；不做清理任务（个人自用量小） |
| D14 | source_dir/output_dir 存绝对路径字符串（展示用途；EntryPoint 引用另存 ep_task_ids） |
| D15 | 单页业务超时 30min（yml `task-timeout-minutes:30`）；到点 cancel(降级)+FAILED(TIMEOUT) |
| D16 | submit HTTP 超时 120s（冷启动宽限）、poll 30s、download 300s |
| D17 | 新表非同步域：不 stamp username、不墓碑；worker 线程不碰 SecurityContext |
| D18 | 输出命名/消费不变：`{cache}/enhanced/{gid}/{page}.{format}` + 阅读器 `?enhanced=1` |
| D19 | 视觉基线只重录 admin-processing（ROUTES 旧路径 slug 惯例不动） |
| D20 | 文档/脚本/仓库**绝不落 token**；冒烟脚本从环境变量读 |

## 12. Commit 计划总表

| 序 | 时机 | 信息（草案） |
|---|---|---|
| 1 | C0 | `feat(web): 图像处理×EntryPoint 契约冻结——…` |
| 2 | W1 末 | `feat(web): EntryPoint 适配器+任务历史落库+前端 API——五操作 client/处理器/写穿 store/去重查询` |
| 3 | W2 末 | `feat(web): 处理编排接线自动化+监控端点+processing 页三区块——下载完成触发/定时补跑/tasks/history/retry` |
| 4 | C8 | `test(web): 刷新 admin-processing 视觉基线 + docs 补录 exec-log` |
| 5 | C10 | `chore(web): 141 部署图像处理管线集成（冒烟+实弹通过）`（如有热修合并在此） |
