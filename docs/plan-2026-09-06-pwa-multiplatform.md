# 开发方案：四端 PWA App——服务器地址可配 + 窗口比例评估台（2026-09-06）

**本文档是给执行 Agent（编排者）的自 contained 交付物。** 你没有参与此前的调查与决策会话——本方案已把全部现状事实、锚点、决策记录、接口契约内嵌，开工前通读本文档 + `CONTEXT.md`（术语表）即可，不依赖任何对话上下文。所有行号锚点基于 HEAD `a891f520`，**执行时以 grep 重定位为准**（本批与 2026-09-06 执行手册批次可能存在先后关系，见 §9）。

你的角色是**编排者（orchestrator）**：本批为多代理并行开发设计，峰值 **6 个子代理同时在飞**。不开子代理亲自写完所有卡是失误；把未消化的卡片原文丢给子代理、不核对锁表就派发，同样是失误。规则见 §7。

---

## 1. 目标与用户可见验收标准

为 AnotherViewer WebUI 提供 PWA App 形态，覆盖 **Android / Windows / Linux / macOS** 四端，两个特殊能力：

1. **服务器地址可配**：用户可输入服务器 IP/地址（当前实例 `http://192.168.6.141:8081`），应用所有 API/WebSocket/图片流量指向该地址；可随时在设置中更换。
2. **窗口比例评估台**：一键切换 **16:9 / 16:10 / 4:3 / 3:2** 四种比例 × 横/竖屏，并可**锁定**，用于快速评估这些常见窗口比例下的使用体验（布局断点真实生效，非视觉伪装）。

验收标准（用户可见，全部满足才算交付）：

- [ ] 四端从浏览器把站点安装为独立窗口 App（standalone、无浏览器 UI）；Android 长按图标有快捷方式（首页/搜索/收藏/**比例评估**）。
- [ ] 首次启动出现非阻塞引导卡「配置服务器地址」；`/setup` 或设置页「服务器地址」可输入 `192.168.6.141:8081`（无 scheme 自动补 `http://`），「测试连接」用 `/api/v1/auth/status` 判定可达性；保存后整页重载生效；换服务器自动清登录态。
- [ ] shell 与数据服务器不同源时（远程模式），列表/详情/阅读器图片/下载进度 WS 全部正常（目标服务器需配 CORS，见 §3.3）。
- [ ] 打开 `/eval`（或图标快捷方式）：4 比例 × 横竖一键切换、等比缩放（适应窗口/100%）、锁定（OS 窗口变化不再重算）；iframe 内布局断点与真实该尺寸窗口**逐像素一致**（e2e 对拍验证，C11）。
- [ ] HTTPS 轨道（§3.3 轨道 B）落地后：141 上 `https://…:8443` 可安装、SW 离线壳生效；HTTP 8081 原样保留（Android App 与既有客户端零扰动）。
- [ ] 全量门绿（typecheck + vitest + gradle test + build.sh）；视觉基线更新；141 已部署并通过 INT-4 四端清单。

**明确不在本批范围**：商店级打包（TWA/MSIX/DMG，个人自用无商店分发需求，否决理由见 §10）；iOS/iPadOS 适配（`public/PWA.md` 已覆盖，保持不回归即可）；Android 源码（`app/`）任何改动；数据库 schema 变更（本批零 DDL）。

---

## 2. 现状盘点（已核验事实与锚点）

### 2.1 前端（web-frontend，Vue3 + Vite + Pinia + vue-router4 + axios + stompjs）

| 事实 | 锚点 | 对本方案的意义 |
|------|------|----------------|
| axios 单例 `baseURL: '/api/v1'`（**同源假设**） | `src/api/client.ts:4-10` | 需求 1 的核心改造点 |
| token 在 localStorage，401 拦截器 `window.location.href='/login'` | `src/api/client.ts:12-18, 82-86` | 切服务器时须清 token；shell 路由始终同源，此逻辑不用动 |
| WS 端点相对路径 `'/ws'`（SockJS，绝对 URL 兼容） | `src/composables/useWebSocket.ts:186, 413` | 需从 server 模块取基址 |
| 手拼 API URL 的位置（图片为主）：siteAsset 代理重写、增强图、下载缩略图、阅读器单页 | `src/utils/siteAsset.ts:57`、`src/composables/useEnhancedImage.ts:139`、`src/components/download/DownloadItem.vue:241`、`src/components/reader/PageMode.vue:159` | 需求 1 改造面；执行时以 `grep -rn "/api/v1" src --include='*.vue' --include='*.ts' \| grep -v __tests__` 全量复核（ScrollMode/ImageReader 若命中一并改） |
| 路由守卫：token → 放行；无 token 查 `/auth/status`，`authRequired=false` 放行 | `src/router/index.ts:91-114` | server-configured 不做硬拦截（决策 D，§4），守卫只加一条 `/setup` 路由 |
| KeepAlive 缓存列表视图（CACHED_VIEWS） | `src/App.vue:70-79` | Setup/Eval 视图不入缓存 |
| SW 仅生产注册，更新流/缓存统计消息协议齐备 | `src/register-sw.ts:17-61` | 沿用，不动注册逻辑 |
| SW 缓存：壳 CacheFirst / 同源 API NetworkFirst+30min TTL / 图片（含跨域）CacheFirst / **跨域非图片不拦截**；`CACHE_NAME='anotherviewer-v2'` | `public/sw.js:28-32, 136, 143, 231-235` | C5 只放宽 136 行的 origin 限制；注意 `public/PWA.md` 写的 v1 已漂移为 v2（顺带修正文档） |
| manifest：standalone/orientation any/maskable/shortcuts 已齐；icons 里有一处无效 `form_factor`（icon 条目不支持该字段，被忽略） | `public/manifest.json:14-19` | C6 清理 + 增「比例评估」shortcut |
| 视觉回归：routes × themes × viewports 矩阵 | `e2e/visual.spec.ts:40-55` | INT-2 增补 /setup、/eval 条目 |
| 实机截图矩阵：4 设备仿真 × 7 路由，`BASE` 环境变量可覆盖 | `e2e/live-responsive.mjs:17-27` | C11 新脚本复用此模式 |
| 响应式 = viewport 媒体查询 + `matchMedia`（`usePcInput.ts`、ReaderView、GalleryCard 等），散布在 15+ 文件 | `grep -rl '@media' src` | **CSS letterbox 无法让断点生效** → 评估台必须用 iframe 真视口（决策 B，§4） |

### 2.2 后端（anotherviewer-web，Spring Boot/Kotlin）

| 事实 | 锚点 | 意义 |
|------|------|------|
| CORS 已有配置口：env `ANOTHERVIEWER_CORS_ORIGINS`，默认仅回环，`allowedOriginPatterns`（支持 `*`+credentials），只映射 `/api/**` | `config/WebConfig.kt:16-30` | 远程模式**后端零代码改动**，纯部署 env |
| WS origins：env `ANOTHERVIEWER_WS_ORIGINS` 默认 `*`（SockJS `/ws`） | `config/WebSocketConfig.kt:16, 38-44` | 远程模式 WS 天然可用 |
| 认证：`require_auth` 默认 false（全员匿名 principal "default"）；`/api/v1/auth/status` permitAll | `ServerConfigService.kt:111`、`SecurityConfig.kt:76`、`AuthTokenFilter.kt:24-29` | status 是最佳探活端点（不受 EH 断连影响；`/api/v1/health` 在 galleryApi DOWN 时返回 503，**不可**作可达性判定） |
| `/api/**`（含图片端点）需认证，且 `<img>` 无法带 Authorization 头——现状图片加载实际依赖 `require_auth=false` | `SecurityConfig.kt:85`、`api/ImageProxyController.kt` | 既有边界，本批不改变也不修复（远程模式继承同一语义），写入 pwa-install.md 边界节 |
| 静态资源由 jar 直接服务；SPA fallback | `config/SpaWebConfig.kt:18-24` | shell 与 API 同源是默认形态 |
| `server.forward-headers-strategy: native`（为反代预留） | `application.yml:6` | HTTPS 轨道走 Caddy 反代零后端改动 |
| 生产实例：`192.168.6.141:8081`，systemd `anotherviewer-web`，jar `/server/AnotherViewer/lib/app.jar`，data-dir 绝不覆盖 | `docs/dev-plan-2026-09-06-execution-handoff.md` §2 | INT-3 部署流程照抄 |

### 2.3 PWA 已有资产

manifest + sw.js + register-sw.ts + 图标（96~512 + apple 四尺寸）+ `public/PWA.md` 文档齐备（roadmap Phase 3.3 遗产）。本批是**增强**（连接层/评估台/HTTPS 可安装性），不是从零建 PWA。

---

## 3. 核心技术定案（含被否决项）

### 3.1 需求 1：服务器地址可配 —— 客户端连接层（定案 A）

新增 `src/stores/server.ts` 模块级单例（非 Pinia——`client.ts` 在模块加载期就要读它），`serverBase` 持久化于 localStorage（归一化后的绝对 origin，如 `http://192.168.6.141:8081`；空串 = 同源，行为与现状逐字节一致）。axios baseURL、全部手拼图片 URL、SockJS 端点统一经它派生。**切换服务器 = 清 token/username + `window.location.reload()`**——不做运行时热切换（单例 WS/axios/KeepAlive 缓存里的旧 URL 清不干净，热切换是为极端场景过度设计）。

- ✅ **定案**：客户端 store + 切换即重载（简单、可穷举验证）。
- ❌ 否决「每服务器同源部署、装多个 PWA」：无法在已安装的 App 内切换，且每台服务器都要可安装（HTTPS），成本更高。
- ❌ 否决「URL scheme handler / 深链协议注册」：浏览器支持残缺，过度设计。

### 3.2 需求 2：窗口比例评估台 —— iframe 真视口（定案 B）

新增独立路由 `/eval`（评估台宿主）：工具条 + 居中 iframe（同源加载 `/`，天然共享 localStorage 登录态）。iframe 的 layout viewport 就是**真实 viewport**——媒体查询、`matchMedia`、`usePcInput` 的指针判定全部真实生效。缩放用 CSS `transform: scale()`（只缩视觉、不缩布局，断点依旧准确）。

- 比例基准分辨率（layout px，横/竖互换即竖屏）：16:9→1280×720；16:10→1280×800；4:3→1024×768；3:2→1200×800。
- 锁定 = 冻结 iframe 尺寸与缩放（OS 窗口 resize 不再重算）；「适应窗口」= `scale=min(availW/w, availH/h)`；「100%」= 原始 px。另附全屏按钮：容器 `requestFullscreen()` + `screen.orientation.lock()` best-effort（Android Chrome 全屏态支持，失败静默降级）。
- 状态全部映射到 URL query（`/eval?ratio=16:9&orient=land&scale=fit&locked=1`），可收藏、可 e2e、可被 manifest shortcut 直达。
- ❌ 否决「CSS letterbox 遮罩容器」：本仓响应式完全由 viewport 断点驱动（§2.1 末行），letterbox 下断点仍看真实窗口，**评估结果是假的**——这与 2026-09-05 以来整批响应式修复的验收口径冲突。
- ❌ 否决「`window.resizeTo()` 真调窗」：安装态 PWA 各浏览器均忽略编程调窗，四端不可行；仅作为文档提示（评估台显示"推荐窗口尺寸"供用户手动调窗比对）。
- ❌ 否决「全局悬浮球在任意页面叠加」：悬浮球本身占据/影响被测布局，破坏评估保真度；独立路由 + manifest 快捷方式已满足"快速"。

### 3.3 四端可安装性 —— 安全上下文双轨道（定案 C，本批最容易踩空的坑）

**PWA 安装提示与 Service Worker 要求安全上下文（HTTPS 或 localhost）。当前实例是纯 HTTP `192.168.6.141:8081`——今天在该实例上 SW 注册必然失败、Chrome 不给安装按钮。** 因此方案拆两层：

- **功能层（必做，HTTP 即可用）**：服务器地址输入、比例评估台、manifest/快捷方式——不依赖安全上下文，四端浏览器打开即用。
- **安装层（轨道 B 落地后达成）**：
  - **轨道 A（零部署改动的兜底，仅写入文档）**：各端手动把该 origin 标记为可信——桌面 Chrome/Edge 启动参数 `--unsafely-treat-insecure-origin-as-secure=http://192.168.6.141:8081`；Android `chrome://flags` 同名项。每设备一次性、手动、可失效，只作为兜底写进 pwa-install.md。
  - **轨道 B（定案实施）**：141 上新增 **mkcert 本地 CA + 证书（SAN 含 192.168.6.141）+ Caddy 静态反代**：`https://192.168.6.141:8443` → `127.0.0.1:8081`（含 WS upgrade）。后端**零代码改动**（`forward-headers-strategy: native` 已就绪）；HTTP 8081 原样保留，Android App/既有客户端零扰动。四端设备各安装一次 mkcert root CA（pwa-install.md 给四端步骤），之后安装态 PWA/SW/离线壳全部一等工作。
  - ❌ 否决「Spring 直接上 TLS」：单 connector 一端口一协议，要么换掉 8081 断掉全部既有客户端，要么写双 connector 代码；反代是运维问题，用运维手段解决。
  - ❌ 否决「TWA/MSIX 打包」：商店分发 + 数字资产链接均要求公网 HTTPS 域名，个人内网场景成本收益倒挂。

### 3.4 首次引导形态（定案 D）

`server-configured` 标志**缺省视为"同源已配置"**：不做路由硬拦截。首run 引导 = 首页顶部可关闭的引导卡（`server-setup-dismissed` 持久化），设置页常驻「服务器地址」入口，`/setup` 直达。

- ❌ 否决「未配置即重定向 /setup」：会把现有全部 e2e（visual/drawer/verify-*/live-responsive 的空 localStorage 起跑）和老用户升级体验一起劫持，收益只是"强制看一眼"。

### 3.5 SW 远程模式缓存（定案 E）

跨域 API 缓存判定改为**无状态按路径**：`GET && url.pathname.startsWith('/api/')` 即走 NetworkFirst+TTL（不校验 origin）。axios/XHR 的跨域请求是 cors 模式、非 opaque，可安全缓存；`<img>` 跨域是 no-cors/opaque，仍走既有图片分支。无需向 SW 传递 serverBase（无 INIT 消息协议、无 SW 重启丢态问题）。

---

## 4. 全局约束与红线（每个子代理的派发 prompt 必须附带本节）

1. **打码红线**：`PrivacyMaskFilter`、`privacy.mask_enabled`、API 脱敏链路一行不改（防画廊站点风控，完整保留；威胁模型=私有网络单用户）。
2. **`app/` 与 `anotherviewer-core/` 只读**；本批后端**零 Java/Kotlin 代码改动**（C10 只产出脚本/配置模板/文档；若发现必须改后端代码才能达成，停下来升级给用户）。
3. **实例访问纪律**：`192.168.6.141:8081` 仅用于部署与打码形态走查；不得用 curl/脚本抓取画廊内容/图片/文件。
4. **凭据不入库**：SSH 密码、mkcert CA 私钥 passphrase 等由派发渠道提供，绝不写入仓库文件/commit/日志。
5. **零 schema 变更、不升级依赖**：不引入 `vite-plugin-pwa` 等任何新依赖（手写 SW 是现状契约）；SW 的 `CACHE_NAME` bump（v2→v3）**只允许 C5 做一次**。
6. **不动无关代码**：不顺手重构、不改 `--hamburger-clearance` 等样式契约、不动既有视觉基线语义。
7. **commit 风格**：conventional commits + 中文主题行（对齐 `git log`）；执行期本地 commit，验收通过后统一 push；按卡精确 `git add <文件清单>`，**禁止 `git add -A` / `git add .` / `git add docs/`**。
8. **工作文档不 commit**：本方案、exec-log、audit 等 `docs/*2026-09-06*` 过程文档永不 git add（历史已入库的 plan-* 不动）。
9. **同源零回归是硬约束**：`serverBase` 为空时，所有 URL 输出与改造前**逐字节一致**（C1/C2 的 spec 必须固化这一点）。

---

## 5. 环境与命令速查

| 用途 | 命令 |
|------|------|
| 前端单测 / 类型检查 | `cd web-frontend && npm test` / `npm run typecheck` |
| 前端构建 | `npm run build`（产物进 `anotherviewer-web/src/main/resources/static`） |
| 视觉回归 / 基线刷新 | `npm run test:visual` / `npm run test:visual:update` |
| 多设备实机截图 | `node e2e/live-responsive.mjs [OUT_DIR]`（`BASE`/`GID` 可覆盖） |
| 比例矩阵截图（本批新增） | `node e2e/aspect-matrix.mjs [OUT_DIR]`（C11 产出） |
| 后端单测 | `./gradlew --configure-on-demand :anotherviewer-web:test` |
| 整包构建 | `./build.sh`（core→前端→bootJar，含 dist 新鲜度门） |
| 本地双实例联调（远程模式验证） | 本地跑 jar：`java -jar anotherviewer-web/build/libs/anotherviewer-web-*.jar --server.port=8090`，shell 用 `npm run dev`（`VITE_PROXY_TARGET` 指向 8080），serverBase 指向 8090 |
| 部署（INT-3） | `./build.sh` → scp jar 到 `lib/app.jar.new` → 备份 `app.jar.bak-YYYYMMDD` → 原子替换 → `sudo systemctl restart anotherviewer-web` → `curl -s localhost:8081/api/v1/health`（启动约 18s） |

---

## 6. 依赖图与波次总览

```
W0 自举（编排者亲自）
 ├─ W1（6 卡并行，无相互依赖；C7/C8 靠"契约先行"提前到 W2 的接口已冻结在卡内）：
 │    C1 server 连接核心 | C2 URL 构造点接入 | C3 WS 端点 | C4 视口框架 composable
 │    C5 SW 远程缓存 | C6 manifest 清理
 ├─ W2（4 卡并行，消费 W1 契约）：
 │    C7 Setup 向导+设置入口（依赖 C1 契约） | C8 EvalView 评估台（依赖 C4 契约）
 │    C9 四端安装文档 | C10 HTTPS 轨道 B 资产（脚本/反代模板/部署文档）
 ├─ W3（1 卡）：C11 比例矩阵 e2e + 对拍（依赖 C8 合并）
 └─ INT 收口：全量门 → 视觉基线 → 部署(8081+8443) → 四端验证（Linux/Android 自动化 + Win/macOS 人工清单）
```

波内并行、波间串行。峰值 6 子代理（W1），此时编排者只做调度/验收。INT 期可再开 2-3 个验证代理（§8 INT-4）。

---

## 7. 多代理协作规则（挑战编排者）

### 7.1 任务卡协议

子代理 prompt = 卡片全文 + §4 红线 + §5 中它需要的命令。**卡片必须原样完整复制**——子代理没有本对话上下文，缺一句背景就会做错误假设。产出契约：①改动只落在卡片锁定的文件集；②跑完卡片指定测试并附结果；③报告 diff 摘要与任何偏离；④**不 commit**（提交权在编排者）。

### 7.2 文件域锁（并行安全的唯一保证）

「文件（锁）」是该代理唯一可写文件集。**同一文件不得同时属于两张在飞卡**——派发前必须核对锁表两两不相交。发现两卡必须动同一文件：串行化，或把交叉部分拆给先卡。默认共享工作树；发生跨卡污染才升级 `git worktree` 隔离（每 worktree 要 `npm ci`，成本高，慎用）。

### 7.3 契约先行（本批并行度的关键）

W1 的 C1（server 模块）与 W2 的 C7（Setup 界面）、W1 的 C4（视口框架）与 W2 的 C8（EvalView）是消费关系。**接口签名已冻结在卡内**（§8 C1/C4 的 TS 契约块）——消费方按契约先行开发（可先以本地 stub 通过 typecheck），W2 首个工作项是删除 stub 换真实现。契约本身不得单方面变更；确需变更，停卡上报编排者统一修订两张卡。

### 7.4 编排者循环

```
for wave in [W1, W2, W3]:
    1. 核对波内文件锁两两不相交（列出锁表）
    2. 一条消息并发派发全部卡片（Agent tool 多 invoke）
    3. 每卡归来：git status 查越锁、抽查 diff、跑该卡 scoped 测试
    4. DoD 全过 → 按卡 commit；越锁/测试红 → 退回修复或编排者亲自修，不放宽标准
波间：全量门（typecheck + npm test + gradle test）通过才进下一波
```

**编排者写码仅限两种情形**：退卡修复失败后的兜底修补、INT 收口的小补。除此之外亲自实现整卡=失误。

### 7.5 验证工具的分工

- 子代理：Playwright 脚本、vitest、gradle、node 脚本、`android-emulator` MCP 工具（构建/安装/截图/自动化）。
- **编排者亲自**：browser-use 浏览器走查（该 skill 主代理限定，不得下放子代理）；凭据操作；部署。

---

## 8. 任务卡全集（TODO list）

规模：S=小时级 M=半天级 L=一天级。所有行号锚点执行时以 grep 重定位。

### W0 自举（编排者亲自）

**T0 环境与基线** [S]
- 通读本方案 + `CONTEXT.md`；`git status` 干净、记录 HEAD；跑通 `npm test`、`npm run typecheck`、`:anotherviewer-web:test` 记录基线（既有红测试记录在案、排除归因）。
- 创建 `docs/exec-log-2026-09-06-pwa.md`（每卡一行：ID/结论/commit hash；未跟踪工作文档，不 commit）。
- **核对 §9 冲突表**：确认 2026-09-06 执行手册批次不在同一工作树在飞；对其已合并的卡，重定位本方案受影响锚点并写入 exec-log。

### W1（6 卡并行）

**C1 server 连接核心模块** [M]
- 文件（锁）：新增 `src/stores/server.ts`、新增 `src/stores/__tests__/server.spec.ts`、`src/api/client.ts`、`src/api/__tests__/client.spec.ts`（如不存在则新增）
- 契约（冻结，C7 消费）：
  ```ts
  export const SERVER_BASE_KEY = 'server-base'        // 归一化绝对 origin；''=同源
  export const SERVER_CONFIGURED_KEY = 'server-configured' // '1'=已显式配置过
  export class ServerBaseError extends Error {}        // 归一化失败（非法 URL/scheme）
  export function normalizeServerBase(raw: string): string
    // ''→''；无 scheme 补 'http://'；去尾部 '/' 与路径（只留 origin）；
    // 仅接受 http/https 且 new URL() 可解析，否则 throw ServerBaseError
  export function getServerBase(): string              // 读 localStorage，异常容错回 ''
  export function isServerConfigured(): boolean
  export function setServerBase(raw: string): string   // normalize+持久化，返回归一化值
  export function apiBaseUrl(): string                 // base ? `${base}/api/v1` : '/api/v1'
  export function resolveApiUrl(path: string): string  // path 以 '/' 开头且不含 /api/v1 前缀
  export function wsUrl(): string                      // base ? `${base}/ws` : '/ws'
  export const serverBase: Ref<string>                 // 响应式镜像（UI 显示用）
  ```
- 指令：`client.ts` 的 `baseURL` 改为 `apiBaseUrl()`（模块加载期求值一次即可——切换走整页重载）；新增并导出 `applyServerBase()`（重设 `client.defaults.baseURL`，供测试与稳妥），其余拦截器逻辑一行不动。`main.ts` 的 privacyApi 启动拉取自然经新 baseURL，无需改。
- 验收：normalize 矩阵用例（空/host/host:port/带路径/带尾斜杠/https/非法输入 throw）；`serverBase=''` 时 `apiBaseUrl()==='/api/v1'`、`wsUrl()==='/ws'`（零回归锚点）；设置后前缀正确；client spec 补 baseURL 派生用例。

**C2 URL 构造点接入** [M]
- 文件（锁）：`src/utils/siteAsset.ts`、`src/composables/useEnhancedImage.ts`、`src/components/download/DownloadItem.vue`、`src/components/reader/PageMode.vue`、以及执行时 grep 命中的其它非测试文件（`grep -rn "/api/v1" src --include='*.vue' --include='*.ts' | grep -v __tests__ | grep -v client.ts | grep -v server.ts`——把实际清单写进 exec-log 后再动手）、上述文件对应 spec
- 指令：所有手拼 `/api/v1/...` 字符串改经 `resolveApiUrl()`（注意 `resolveApiUrl('/image/proxy?url=…')` 的参数形态与现状 path 拼接等价）。**零回归锚点**：`serverBase=''` 时产出 URL 与改前逐字节一致，spec 用快照断言固化。
- 验收：新增用例（serverBase=`http://x:1` 时各构造点前缀正确 + 空串时与旧值一致）；typecheck 绿。

**C3 WS 端点接入** [S]
- 文件（锁）：`src/composables/useWebSocket.ts`、`src/composables/__tests__/useWebSocket.spec.ts`（如不存在则新增，mock SockJS 断言构造入参即可）
- 指令：`WS_ENDPOINT` 常量删除，`ensureClient()` 的 `webSocketFactory` 改 `new SockJS(wsUrl())`（单例在切换后整页重载时自然重建，不做热切换）。
- 验收：spec 断言同源 `'/ws'` 与远程 `http://x:1/ws` 两种构造参数；typecheck 绿。

**C4 视口框架 composable** [M]
- 文件（锁）：新增 `src/composables/useViewportFrame.ts`、新增 `src/composables/__tests__/useViewportFrame.spec.ts`
- 契约（冻结，C8 消费）：
  ```ts
  export type AspectRatio = '16:9' | '16:10' | '4:3' | '3:2'
  export type Orientation = 'landscape' | 'portrait'
  export type ScaleMode = 'fit' | '100'          // 适应窗口 / 原始像素
  export const CANONICAL: Record<AspectRatio, { w: number; h: number }>
    // '16:9':1280×720  '16:10':1280×800  '4:3':1024×768  '3:2':1200×800
  export interface FrameState { ratio: AspectRatio; orientation: Orientation
    scaleMode: ScaleMode; locked: boolean }
  export function parseFrameQuery(q: Record<string, unknown>): Partial<FrameState>
  export function useViewportFrame(initial?: Partial<FrameState>) {
    return { /* 均 Ref */ ratio, orientation, scaleMode, locked,
      width, height,          // 当前 layout px（portrait 时 w/h 互换）
      scale,                   // fit: min(availW/width, availH/height)；'100': 1；locked: 冻结
      setRatio, setOrientation, toggleOrientation, setScaleMode, toggleLock,
      toQuery(): Record<string, string> }   // {ratio, orient, scale, locked}
  }
  ```
- 指令：纯逻辑 + `window` resize 监听（happy-dom 可测）；`availW/availH` 由构造入参或 `window.innerWidth/Height - 工具条高(56)` 推导，工具条高作为常量导出（`EVAL_TOOLBAR_H = 56`）。锁定语义：`locked=true` 后 resize 不重算 `scale`（width/height 本就只随比例变）。
- 验收：矩阵用例（4 比例×2 方向的 width/height；fit 数值计算；锁定后 resize scale 不变；query 解析/序列化往返；非法 query 回默认 16:9/landscape/fit/unlocked）。

**C5 SW 远程 API 缓存** [M]
- 文件（锁）：`public/sw.js`、`public/PWA.md`
- 指令：`fetch` 处理器中 API 分支（现 `sw.js:136` 的 `url.origin === self.location.origin && url.pathname.startsWith('/api/')`）改为**去掉 origin 校验**、仅 `request.method==='GET' && url.pathname.startsWith('/api/')` 即 NetworkFirst+TTL——跨域 cors 响应（axios）与同源同策略；opaque（`<img>` no-cors）不进此分支（仍走图片分支，与本批前 W3-F6 的 opaque 处理互不冲突）。`CACHE_NAME` bump `anotherviewer-v2`→`anotherviewer-v3`（全批唯一一次）。`PWA.md` 同步：策略表加"跨域 API"行、版本号漂移（v1→现值）修正、§7 已知边界补"远程模式离线依赖目标服务器 API 可缓存（cors 响应）"。
- 验收：本地验证脚本 `e2e/verify-remote-sw.mjs`：起一个 stub HTTP 服务（node，返回带 CORS 头的 `/api/v1/auth/status`），页面（`vite preview` 或本地 jar）把 serverBase 指向 stub → 断言 SW api 缓存出现 stub 条目、断网后命中缓存（或 DoD 降级为：DevTools 不可用环境下以 `caches.keys()/match` 在页面内断言）。脚本入 `e2e/`（锁内新增文件允许）。

**C6 manifest 清理与核验** [S]
- 文件（锁）：`public/manifest.json`、新增 `e2e/verify-manifest.mjs`
- 指令：icons 数组删除 512 条目上的无效 `form_factor` 字段（icon 条目不支持，被浏览器忽略）；shortcuts 增 `{ name:'比例评估', short_name:'比例评估', url:'/eval', description:'窗口比例评估台' }`；其余字段（id/display/orientation/theme）不动。`verify-manifest.mjs`：拉取 `/manifest.json` + 全部 icons 断言 200、shortcut url 可达、JSON 可解析。
- 验收：脚本对本地 preview/141 全绿；manifest 通过 Chrome DevTools > Application > Manifest 的零告警（编排者 INT 期亲自 browser-use 核验一次）。

### W2（4 卡并行；C7/C8 首项是"删除契约 stub 换 C1/C4 真实现"）

**C7 Setup 向导 + 设置入口** [M] 依赖 C1 合并
- 文件（锁）：新增 `src/views/SetupView.vue`、新增 `src/views/__tests__/SetupView.spec.ts`、`src/router/index.ts`（仅加一条路由）、`src/App.vue`（仅引导卡挂载）、新增 `src/components/setup/SetupHintCard.vue`、`src/views/settings/GeneralSettings.vue`（仅「服务器地址」行）
- 指令：
  - 路由：`{ path: '/setup', name: 'Setup', component: … }`（守卫逻辑不动——定案 D）。
  - SetupView：输入框（placeholder `192.168.6.141:8081`）+ 归一化预览 + 「测试连接」（`GET ${base}/api/v1/auth/status`，**任何 HTTP 响应（200/401/5xx）都算服务器可达**，网络层错误才算不可达；不用 /health——EH 断连会让它 503）+ 结果态（版本/是否要求登录）+ 「保存并重载」（`setServerBase` + 清 token/username + `window.location.reload()`）+ 「使用当前地址」快捷键（预填 `window.location.origin`）。**https shell → http 目标**：保存前检测 `location.protocol==='https:' && base.startsWith('http:')` → 行内警告"浏览器将拦截混合内容"，允许保存但红色提示。
  - GeneralSettings 增「服务器地址」行：显示当前 base（空则"同源部署"），点击跳 `/setup`。
  - App.vue 增 `SetupHintCard`：`v-if="route.name==='Home' && !isServerConfigured() && !dismissed"`，可关闭（`server-setup-dismissed='1'` 持久化），两个动作（去配置 / 进入比例评估）。
- 验收：spec——归一化/测试连接三分支（成功/HTTP错误=可达/网络错误=不可达）/保存调用序列/https→http 警告出现；路由 `/setup` 可达且不劫持其它路由；typecheck 绿。

**C8 EvalView 评估台** [L] 依赖 C4 合并
- 文件（锁）：新增 `src/views/EvalView.vue`、新增 `src/components/eval/EvalToolbar.vue`、新增 `src/views/__tests__/EvalView.spec.ts`
- 指令：
  - 布局：顶部工具条（高 `EVAL_TOOLBAR_H`）+ 评估区（暗色 dim 背景，iframe 容器绝对居中，容器外侧角标显示 `1280×720 · 16:9 · 横屏 · 适应 87%`）。
  - 工具条：4 比例 segmented、横/竖 segmented、缩放（适应/100%）、锁定 toggle、全屏（容器 `requestFullscreen()` + `screen.orientation.lock(orientation==='landscape'?'landscape':'portrait')`，均 best-effort 静默降级）、「新标签打开」（`window.open('/')` 兜底；可选加分项：postMessage 向 iframe 取当前路径，2s 超时回退）、退出（`router.back()`，无历史则 push `/`）。
  - iframe：`:src="'/'"` 同源；`width/height` 绑定 composable 的 layout px；外层容器 `transform: scale(scale)` + `transform-origin: top center`；`allow="fullscreen"`；不用 sandbox（需登录态/全屏）。
  - 状态 ↔ URL query 双向（`router.replace({ query: toQuery() })`；进入时 `parseFrameQuery(route.query)`），路由不进 KeepAlive。
- 验收：spec——切换比例/方向后 width/height/角标文案正确；锁定后触发 resize scale 不变；query 往返；iframe style 绑定断言；typecheck 绿。`npm run dev` 手动走查 8 组合截图贴 exec-log（编排者验收时抽查）。

**C9 四端安装与使用文档** [M]
- 文件（锁）：新增 `docs/pwa-install.md`（**注意：这是交付文档，与过程文档不同，属正常 commit 范围**）、`docs/deployment.md`（增补）
- 指令：`pwa-install.md` 内容大纲（必须齐备）：①功能层/安装层分层说明（§3.3）；②四端安装步骤（Android Chrome/Win Edge+Chrome/Linux Chrome+Chromium/macOS Chrome+Edge，及 macOS Safari Add to Dock 指向既有 PWA.md）；③轨道 A 每端 flag/快捷方式配方（含 Win 快捷方式目标行示例、Linux .desktop Exec 示例）；④轨道 B 四端安装 mkcert root CA 步骤 + 日常使用 `https://192.168.6.141:8443`；⑤窗口比例评估台使用指南（入口、8 组合、锁定语义、"推荐窗口尺寸"手动比对表）；⑥degraded 矩阵表（http 下：SW/离线 ✗、安装态各端差异、功能层全 ✓）；⑦远程模式边界（目标服务器需 `ANOTHERVIEWER_CORS_ORIGINS`；require_auth=true 时图片端点因 `<img>` 无 Authorization 头不可用的既有边界）。`docs/deployment.md` 增「HTTPS 轨道 B」与「远程模式 CORS/WS env」两节。
- 验收：文档内所有命令可复制执行（编排者抽 3 条亲自跑）；与 C10 产物的路径互相引用一致。

**C10 HTTPS 轨道 B 资产** [M]
- 文件（锁）：新增 `scripts/gen-lan-cert.sh`、新增 `deploy/caddy-anotherviewer.conf`（模板）、`docs/deployment.md`（与 C9 同文件——**编排者把 C9/C10 串行化或合并派发给同一代理**，锁冲突预案见 §9）
- 指令：`gen-lan-cert.sh`：检测 `mkcert`（无则打印安装指引并以非零退出）→ `mkcert -pkcs12 -cert-file anotherviewer-lan.pem -key-file … 192.168.6.141` 产出至 `deploy/certs/`（gitignore 追加该目录，**私钥绝不入库**）。`caddy-anotherviewer.conf`：`:8443 { tls <cert> <key> ; reverse_proxy 127.0.0.1:8081 }`（含 WS header 透传说明；若目标机已有 nginx 则给等价 server 块附录）。deployment.md 写 systemd 联动（caddy unit 或手工命令）与证书续期（mkcert有效期/重生成）。
- 验收：脚本在本地（有 mkctx 或模拟 PATH stub）dry-run 输出正确；conf 通过 `caddy validate`（本机无 caddy 则静态审查 + INT 期实机验证）。

### W3（1 卡，W2 全合并后）

**C11 比例矩阵 e2e + 真窗口对拍** [M] 依赖 C8
- 文件（锁）：新增 `e2e/aspect-matrix.mjs`、（如需）`e2e/live-responsive.mjs`（仅追加 ASPECT 设备集调用）
- 指令：
  - 矩阵：8 组合（4 比例×2 方向，canonical px，DPR=1）× 4 路由（`/`、`/downloads`、`/settings/general`、`/search`）截图到 `OUT_DIR/aspect-{ratio}-{orient}-{slug}.png`；`BASE` 缺省 `http://192.168.6.141:8081`，打码形态下只看布局。
  - **对拍（核心验收）**：对每组合：A) 真窗口直连（viewport=canonical px）截图；B) 大窗口（1920×1200）打开 `/eval?ratio=&orient=&scale=fit`，等 iframe load，按 iframe 容器 boundingBox 裁剪截图；pixelmatch diffRatio ≤ 0.02（工具链复用 `e2e/compare.mjs` 的 pixelmatch/pngjs）。超阈值输出 diff 图与报告行——这是"评估台=真窗口"承诺的自动化证明。
  - 可选：`live-responsive.mjs` 增补 `desktop-1280x720`/`desktop-1200x800` 两个非触屏设备条目。
- 验收：对本地 dev server（`npm run dev`，需登录态则为 require_auth=false 默认）跑通全矩阵；对拍 8/8 通过；报告路径写 exec-log。

### INT 集成收口（编排者亲自 + 2-3 验证代理并行）

**INT-1 全量门** [S]：`npm run typecheck && npm test && ./gradlew --configure-on-demand :anotherviewer-web:test && ./build.sh`；红则退卡。
**INT-2 视觉基线** [S]：`visual.spec.ts` ROUTES 增 `/setup`、`/eval?ratio=16:9&orient=land`（锁文件核对：属 C11 同域，若已含则跳过）→ `npm run test:visual:update` → `npm run test:visual` 稳定。
**INT-3 构建部署** [M]：`./build.sh` → §5 流程部署 8081 → 在 141 上执行轨道 B：scp 证书（`gen-lan-cert.sh` 本地产出）→ 安装/配置 Caddy（`which caddy nginx` 探测，均无则 `sudo` 安装 caddy 单二进制）→ systemd 起 `:8443` 反代 → `curl -sk https://localhost:8443/api/v1/health` 验证 → **补 systemd env `ANOTHERVIEWER_CORS_ORIGINS=*`**（LAN 单用户威胁模型下的默认决策，见 §10；`systemctl edit` drop-in，写明回滚：删除 drop-in 即恢复）。8081 行为回归验证（health + App 同步不受影响）。
**INT-4 四端验证** [M]（打码形态，禁抓内容）：
- Linux（自动化，子代理）：Playwright/Chromium 对 `https://…:8443`（导入 CA 或 `--ignore-certificate-errors` 仅限验证）跑 manifest 校验、SW 注册（`navigator.serviceWorker.controller` 非空）、离线壳（断网 reload 仍出壳）、`/eval` 8 组合、远程模式（shell=8443、serverBase 指向 8090 stub 或 8081 自身跨端口）。
- Android（编排者亲自或子代理用 android-emulator MCP）：模拟器装 root CA → Chrome 打开 8443 → 安装到主屏 → 独立窗口启动 → 长按图标快捷方式（含比例评估）→ `/eval` 全屏+方向锁 → 截图矩阵入 exec-log。
- Windows/macOS（人工清单）：`pwa-install.md` §对应端清单导出为 checklist 交用户，附对拍截图参考。
- 编排者亲自 browser-use：Chrome DevTools Manifest 零告警核验、安装按钮出现、setup 流程完整走一遍（输入 141:8081 → 测试连接 → 保存重载 → 首页出数据）。
**INT-5 收口** [S]：exec-log 完整；四端清单状态汇总；**push 前须用户确认 INT-4 结果**。

---

## 9. 与 2026-09-06 执行手册批次（dev-plan-2026-09-06-execution-handoff.md）的关系

两批次存在文件锁交叠，**同一工作树绝不允许两批同时在飞**：

| 交叠文件 | 本批卡片 | 对方批次卡片 | 规则 |
|----------|----------|--------------|------|
| `src/api/client.ts` | C1 | W3-F6 ⑤ | 后落者 rebase；本批 C1 只动 baseURL 派生，diff 面小 |
| `public/sw.js` | C5 | W3-F6 ④ | C5 的 origin 放宽与 F6 的 opaque 跳过语义互补，后落者合并时保留双方 |
| `src/router/index.ts` | C7 | A5-1 | C7 只增一条路由，冲突极小 |
| `src/views/settings/GeneralSettings.vue` | C7 | A5-2 | A5 若已把 admin 并入 settings，重定位入口行落点 |
| `src/App.vue` | C7 | A5-2 | 同上，引导卡挂载点重定位 |
| `application.yml` | 无（C10 零后端改动） | W1-B2 | 无冲突，列出仅为确认 |
| `docs/deployment.md` | C9/C10 | W1-B2 | 同文件追加不同章节，串行合并 |

编排者 T0 必须判定：对方批次未启动（直接开工本批）/在飞（等待或换工作树）/已合并（按上表重定位锚点后开工）。C9 与 C10 同锁 `docs/deployment.md` → **不得同时在飞**，串行派发或合并为单代理两卡。

---

## 10. 默认决策表（编排者遇未列事项按此执行，不等待用户）

| 事项 | 默认决策 |
|------|---------|
| server-configured 缺省语义 | 同源已配置（不劫持路由），引导卡可见但非阻塞 |
| 切换服务器 | 清 token/username + 整页 reload，不做热切换 |
| 输入归一化 | 无 scheme 补 `http://`；只留 origin；非法抛错行内提示 |
| 可达性探测端点 | `/api/v1/auth/status`（任何 HTTP 响应=可达）；禁用 /health 判定 |
| SW 跨域 API 判定 | 无状态按 pathname（`/api/` 前缀 GET），不校验 origin |
| canonical 尺寸 | 16:9=1280×720；16:10=1280×800；4:3=1024×768；3:2=1200×800 |
| 评估台缩放档位 | 适应窗口 / 100% 两档（fill 会破坏比例，不做） |
| 对拍阈值 | pixelmatch diffRatio ≤ 0.02，超阈值报告而非放宽 |
| 141 上 CORS env | 设 `ANOTHERVIEWER_CORS_ORIGINS=*`（LAN 单用户威胁模型；drop-in 可回滚） |
| HTTPS 形态 | Caddy 反代 :8443，mkcert 证书 SAN=192.168.6.141；8081 协议不变 |
| 商店打包（TWA/MSIX） | 不做（个人内网，无分发需求） |
| Android 方向锁 | 全屏态 best-effort，失败静默降级为锁定比例 |
| 需要新增 DB 列 / 改后端代码 | 禁止——升级给用户 |
| 子代理越锁 | 退回：revert 越锁改动，编排者归卡或另开卡 |
| 既有红测试 | 记录 exec-log、排除归因，不为转绿改无关测试 |
| 私钥/证书产物 | `deploy/certs/` 入 .gitignore，绝不 commit |

---

## 11. 交付物定义

全部完成后：`BiLi_PC_Gamer` 上按卡一 commit 的历史 + 更新的视觉基线 + `docs/pwa-install.md`/`docs/deployment.md` 交付文档 + `scripts/gen-lan-cert.sh`/`deploy/caddy-anotherviewer.conf` + `e2e/aspect-matrix.mjs`/`e2e/verify-remote-sw.mjs`/`e2e/verify-manifest.mjs` + `docs/exec-log-2026-09-06-pwa.md` 执行日志（不 commit）+ 141 已部署（8081 更新 + 8443 HTTPS + CORS env）并通过 INT-4。最终 push 在用户确认 INT-4 后执行。

---

## 12. 实现者注意事项（已知的坑）

1. **`/api/v1/health` 在 EH 断连时 503**——可达性判定只能用 `/auth/status`（permitAll 且不依赖上游）。
2. **SW 仅生产注册**（`register-sw.ts` `import.meta.env.PROD`）：C5 的验证必须走 `vite preview` 或本地 jar/141，dev server 下 SW 根本不存在。
3. **vite preview 的 proxy 继承**：`preview` 默认沿用 `server.proxy` 配置；若行为不符，SW 验证直接对 141（打码形态）做。
4. **vue-router 可选参数缺省是 `''`**（`/reader/:gid/:page?` 前科）：评估台用 query 传参（`parseFrameQuery` 全部 `typeof === 'string' && 非空` 校验），不踩 params。
5. **iframe 与外壳共享 localStorage（同源）**：登录态/偏好天然同步；但 Pinia store 是两个独立实例——主题切换在外壳改不会即时传导进 iframe，预期行为（在 iframe 内切换即可），写进 pwa-install.md 说明，不要为此做 postMessage 同步。
6. **SockJS 端点传绝对 URL 用 `http(s)://`**，不要写成 `ws://`（SockJS 自己协商升级）。
7. **`transform: scale()` 不改 layout viewport**——这正是对拍能全等的原理；不要顺手改成 `zoom`（Chrome 语义漂移且影响布局）。
8. **KeepAlive**：EvalView/SetupView 不得进 CACHED_VIEWS；Setup 保存后 reload，勿尝试不刷新切换。
9. **CORS 只映射 `/api/**`**（WebConfig），WS 走 `ANOTHERVIEWER_WS_ORIGINS`（默认 `*`）——远程模式排障先分清是哪一个拦的（浏览器 console 的 CORS 报错区分 API vs SockJS 握手）。
10. **`deploy/certs/` 必须进 .gitignore 再生成证书**，顺序不能反；mkcert 的 root CA 安装状态影响四端验证，pwa-install.md 步骤要写"安装后重启浏览器"。
11. **android-emulator 模拟器时钟/网络**：模拟器访问宿主 LAN IP 直通，无需 10.0.2.2 特殊地址（那是宿主回环用的）；141 是独立主机，直接访问。
12. **既有视觉基线**：本批不改既有路由形态，INT-2 只**新增**条目；若 `test:visual` 出现既有路由 diff，说明某卡改了共享样式——退卡排查，不刷基线掩盖。
13. **PWA.md 的 v1 漂移**：现值已是 `anotherviewer-v2`，C5 bump 到 v3 时把 PWA.md 全文版本引用一并校正。
