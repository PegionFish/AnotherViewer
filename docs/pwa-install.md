# AnotherViewer PWA 安装与内网 HTTPS 指南

面向局域网用户：如何把 AnotherViewer WebUI 装成桌面/主屏幕应用（standalone PWA），以及没有域名、只有内网 IP（`192.168.6.141`）时如何解锁安装能力。服务器侧部署步骤（mkcert 证书生成、Caddy/nginx 反代、CORS env）见 `docs/deployment.md` 的「HTTPS 轨道 B（LAN 内网 HTTPS 反代）」与「远程模式 CORS/WS env」两节；SW/离线实现细节见 `web-frontend/public/PWA.md`。

本文涉及的服务器侧产物：`scripts/gen-lan-cert.sh`（证书生成脚本）、`deploy/caddy-anotherviewer.conf`（Caddy 配置）、`deploy/certs/`（证书与私钥，不入库）。

## 1. 功能层 / 安装层：两层能力分层

AnotherViewer WebUI 的 PWA 能力分两层，依赖条件不同：

| 层 | 能力 | 依赖 |
| --- | --- | --- |
| **功能层** | 服务器地址配置（首次引导卡 / 设置页「服务器地址」入口 / `/setup` 直达；测试连接、保存后整页重载、换服务器自动清登录态）、窗口比例评估台 `/eval`、路由 URL 直达（书签/主屏快捷方式指向 `/eval?ratio=...` 等） | **纯 HTTP 8081 即可用**，不依赖安全上下文 |
| **安装层** | Service Worker 注册（离线缓存：CacheFirst 壳 / NetworkFirst + 30 分钟 TTL 的 API（含跨域 `/api/` 前缀 GET）/ 图片 CacheFirst（500 条 30 天））、浏览器「安装」入口（standalone 独立窗口、manifest 快捷方式菜单：首页/搜索/收藏/比例评估） | **安全上下文**（HTTPS 或 localhost）；`http://192.168.6.141:8081` 上 SW 注册失败、Chrome 无安装按钮 |

解锁安装层有两条轨道：

- **轨道 B（推荐）**：mkcert 本地证书 + 内网 HTTPS 反代（`:8443`），一次部署全设备受益，见第 4 节；
- **轨道 A（不推荐，兜底）**：Chrome 内部 flag 把该 HTTP origin 当作安全上下文，见第 3 节。

两条轨道都**不改动 HTTP 8081 本身**——Android App 与既有 HTTP 客户端零扰动；不装轨道时功能层照常可用。

## 2. 四端安装步骤

以下步骤的前提：已按第 4 节（轨道 B）拿到安全上下文，统一访问 `https://192.168.6.141:8443`，且页面里 SW 已注册成功（地址栏出现安装图标）。若安装入口不出现，先对照第 6 节矩阵排查。

### Android（Chrome）

1. Chrome 打开 `https://192.168.6.141:8443`。
2. 页面出现「添加到主屏幕」安装横幅，或菜单 ⋮ →「添加到主屏幕」/「安装应用」→ 确认。
3. 桌面出现 AnotherViewer 图标，点开即 standalone 独立窗口（无地址栏）。
4. 长按图标出现快捷方式：首页 / 搜索 / 收藏 / **比例评估**。

### Windows（Edge / Chrome）

- **Edge**：打开站点 → 菜单 ⋯ →「应用」→「将此站点作为应用安装」→ 确认。可在开始菜单固定，独立窗口运行。
- **Chrome**：地址栏右侧出现安装图标（⊕），点击 →「安装」；或菜单 ⋮ →「投放、保存和分享」→「安装页面…」（不同版本菜单层级略有差异，认准「安装/Install」字样）。

### Linux（Chrome / Chromium）

- **Chrome**：同 Windows Chrome——地址栏安装图标，或菜单 ⋮ →「安装页面」（部分版本在「投放、保存和分享」子菜单）。
- **Chromium**：地址栏安装图标 →「安装」；旧版本若无安装项，菜单 ⋮ →「更多工具」→「创建快捷方式…」并勾选「在窗口中打开」（效果接近 standalone）。

### macOS（Chrome / Edge / Safari）

- **Chrome**：地址栏安装图标，或菜单 ⋮ →「安装页面」。
- **Edge**：菜单 ⋯ →「应用」→「将此站点作为应用安装」。
- **Safari**：菜单栏「文件」→「添加到程序坞（Add to Dock）」→ 确认。Safari 不走 Chromium 的安装流程，添加后从程序坞启动为独立窗口；iOS/iPadOS 的「添加到主屏幕」行为与验证清单详见 `web-frontend/public/PWA.md`。

## 3. 轨道 A（不推荐，兜底）：把 HTTP origin 标记为安全

Chrome/Edge/Chromium 有内部 flag **`--unsafely-treat-insecure-origin-as-secure`**，把指定 origin（此处为 `http://192.168.6.141:8081`）当作安全上下文，从而在纯 HTTP 上解锁 SW 注册与安装态。不装 mkcert 根 CA、不动服务器，适合临时验证；缺点见本节末尾。

> flag 生效的是整台浏览器实例，不是单个站点；只想隔离使用时可追加 `--user-data-dir=<独立目录>` 参数。

### Windows（Chrome / Edge 快捷方式目标行）

右键快捷方式 →「属性」→「目标」整行替换（保留外层引号，flag 与引号之间留一个空格），确定后**先完全退出浏览器（含托盘后台进程）**再从该快捷方式启动：

```text
"C:\Program Files\Google\Chrome\Application\chrome.exe" --unsafely-treat-insecure-origin-as-secure=http://192.168.6.141:8081
```

```text
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --unsafely-treat-insecure-origin-as-secure=http://192.168.6.141:8081
```

### Linux（.desktop Exec 行）

`~/.local/share/applications/anotherviewer-unsafe.desktop`：

```ini
[Desktop Entry]
Type=Application
Name=AnotherViewer (HTTP unsafe-secure)
Exec=/usr/bin/google-chrome-stable --unsafely-treat-insecure-origin-as-secure=http://192.168.6.141:8081 http://192.168.6.141:8081
```

（Chromium 把 `Exec` 首段换成 `/usr/bin/chromium`。）

### Android（chrome://flags 同名项）

Chrome 地址栏输入 `chrome://flags` → 搜索 `unsafely` → 找到 **Unsafely treat insecure origin as secure**（与本 flag 同名）→ 文本框填入 `http://192.168.6.141:8081`、下拉选 **Enabled** → 点 **Relaunch** 重启浏览器。

### 已知代价（如实）

- **每设备一次性、手动配置**：换设备要重来，换浏览器实例（`--user-data-dir` 不同）也要重配；
- **浏览器版本升级可能失效**：Chrome/Edge 随时可能移除该 flag；升级后需检查重配；
- 只对配的那个 origin 生效；整实例放宽安全语义，不适合日常浏览器。

## 4. 轨道 B（推荐）：mkcert 内网 HTTPS

一次部署，所有设备拿到真正的安全上下文：

1. **服务器侧**（141）：`./scripts/gen-lan-cert.sh` 生成证书到 `deploy/certs/`；用 `deploy/caddy-anotherviewer.conf` 起 Caddy 反代 `:8443 → 127.0.0.1:8081`。详细步骤（含 systemd unit、nginx 等价配置、续期）见 `docs/deployment.md`「HTTPS 轨道 B（LAN 内网 HTTPS 反代）」。
2. **客户端侧**：把 141 上 `$(mkcert -CAROOT)/rootCA.pem` 安全拷贝到各设备（如 `scp`、U 盘）并安装（下文四端步骤）。
3. **日常使用**：访问 `https://192.168.6.141:8443`（不是 8081；8081 继续服务 Android App 等既有 HTTP 客户端）。

> ⚠️ **安装 CA 后必须完全退出并重启浏览器**（部分 Android 机型需重启设备）——浏览器仅在启动时加载信任库，否则访问仍报 `NET::ERR_CERT_AUTHORITY_INVALID`。

### 安装 mkcert 根 CA（四端）

**Windows**

1. 双击 `rootCA.pem` →「安装证书」→ 存储位置选「当前用户」（或「本地计算机」）。
2. 选「将所有的证书都放入下列存储」→「浏览」→ 选「**受信任的根证书颁发机构**」→ 完成，弹窗选「是」。
3. 完全退出并重启 Edge/Chrome。

**Linux**

Debian/Ubuntu（读系统信任库）：

```bash
sudo cp rootCA.pem /usr/local/share/ca-certificates/mkcert-rootCA.crt
sudo update-ca-certificates
```

Arch：

```bash
sudo trust anchor --store rootCA.pem
# 等价：sudo cp rootCA.pem /etc/ca-certificates/trust-source/anchors/ && sudo update-ca-trust
```

Chrome/Chromium 读系统信任库，上述命令即可；**Firefox 有独立证书库**，需在其「设置 → 隐私与安全 → 证书 → 查看证书 → 证书颁发机构 → 导入」手动导入并勾选信任网站。

**macOS**

1. 双击 `rootCA.pem` →「钥匙串访问」导入到「**系统**」钥匙串。
2. 在该证书详情的「信任」区把「使用此证书时」改为「**始终信任**」。
3. 重启浏览器。

**Android**

1. 把 `rootCA.pem` 拷到手机存储。
2. 「设置」→「安全」→「更多安全设置」→「加密与凭据」→「安装证书」→「**CA 证书**」（厂商路径命名有差异，可在设置里搜「安装证书」或「从存储设备安装」）→ 选中 `rootCA.pem` → 过程中可能要求设置锁屏 PIN。
3. 杀掉浏览器进程重开（Chrome 信任用户级 CA，PWA 访问不受 Android「应用默认不信任用户 CA」策略影响）。

## 5. 窗口比例评估台（`/eval`）使用指南

评估台用于确定「阅读器在你设备/窗口尺寸下应锁定哪种宽高比」。

**入口**

- 直达 URL：`https://…/eval`；
- manifest 快捷方式「比例评估」（安装态下长按应用图标出现）；
- URL query 直达组合，如 `/eval?ratio=16:9&orient=land`。

**8 种组合**：4 种比例 × 横竖屏，比例的 canonical 尺寸如下（评估台按此渲染 iframe **真视口**，媒体断点真实生效，不是模拟截图）：

| 比例 | 横屏（landscape） | 竖屏（portrait） |
| --- | --- | --- |
| 16:9 | 1280×720 | 720×1280 |
| 16:10 | 1280×800 | 800×1280 |
| 4:3  | 1024×768 | 768×1024 |
| 3:2  | 1200×800 | 800×1200 |

**缩放两档**

- 「适应窗口」：整个目标视口缩放塞进当前窗口，看整体布局；
- 「100%」：原始像素渲染，超出部分滚动，看真实字号/留白。

**锁定语义**：点「锁定」后，OS 窗口尺寸变化**不再触发重算**——先把窗口大致拖到位再锁定，然后微调窗口观察断点切换；解锁恢复跟随窗口重算。

**「推荐窗口尺寸」手动比对**：把本机浏览器窗口手动调到上表尺寸（配合系统分屏/窗口管理工具），与评估台渲染结果对照，确认断点行为与真机一致。

## 6. degraded 矩阵：纯 HTTP 8081 下的能力差异

不部署任何轨道、直接用 `http://192.168.6.141:8081` 时：

| 能力 | Android Chrome | Windows Chrome/Edge | Linux Chrome/Chromium | macOS Chrome/Edge | macOS/iOS Safari |
| --- | --- | --- | --- | --- | --- |
| SW 注册 / 离线缓存 | ✗ | ✗ | ✗ | ✗ | ✗ |
| 浏览器「安装」入口 | 无安装项；「添加到主屏幕」退化为网页快捷方式（非 standalone） | 无安装图标/菜单项 | 同左 | 同左 | 「添加到主屏幕/程序坞」可添加，但为普通网页快捷方式（无 SW/离线） |
| 服务器地址配置（引导卡 / 设置页 / `/setup`） | ✓ | ✓ | ✓ | ✓ | ✓ |
| 比例评估台 `/eval` | ✗（后端 `X-Frame-Options: DENY` 连同源 iframe 也拒显，取景框空白） | 同左 | 同左 | 同左 | 同左 |
| URL 直达快捷方式（书签/主屏指向 `/eval?ratio=...` 等） | ✓ | ✓ | ✓ | ✓ | ✓ |

结论：功能层除 `/eval` 外全绿，受损的还有安装层（SW/离线/standalone）。要安装层与 `/eval`，走第 4 节轨道 B（或第 3 节轨道 A 兜底——但轨道 A 的 flag 只解决安全上下文，**不解 `X-Frame-Options`**，故轨道 A 下 `/eval` 仍不可用）。

> **根因与修复面（INT-4 实测，2026-09-06）**：后端 `SecurityConfig.kt`（M-4 加固）对文档响应固定发 `X-Frame-Options: DENY` 与 `Content-Security-Policy: … connect-src 'self' …`。前者使 `/eval` 的同源 iframe 被浏览器拒显；后者会拦掉远程模式 fetch 与 `/setup` 对非同源主机的「测试连接」。**两处均属平台代码，按红线记录不改**；轨道 B 的反代在代理层做了两处最小放宽（`X-Frame-Options: SAMEORIGIN` + CSP 原策略 `connect-src` 追加 `http: https:`，见 `deploy/caddy-anotherviewer.conf` 头注释），8443 轨道下上述能力全部恢复。

## 7. 远程模式边界

外壳（`https://192.168.6.141:8443`）与数据服务器（`http://192.168.6.141:8081`）不同源，即**远程模式**：API 与图片请求经 serverBase 指向 8081。已知边界（均如实记录、现状如此）：

- **CORS**：目标服务器需用 `ANOTHERVIEWER_CORS_ORIGINS` 放行外壳 origin（如 `http://192.168.6.141:8443`，或可信内网用 `*`）；`allowedOriginPatterns` 支持 `*` 且允许 credentials，仅映射 `/api/**`。配置/回滚步骤见 `docs/deployment.md`「远程模式 CORS/WS env」。
- **WS**：`ANOTHERVIEWER_WS_ORIGINS` 默认 `*`，远程模式通常无需改动。
- **图片端点边界**：`<img>` 标签无法携带 `Authorization` 头——目标服务器 `require_auth=true` 时，**远程模式下的图片端点不可用**（既有边界，与 PWA 无关）；`require_auth=false` 时正常。
- **主题同步**：外壳与 iframe 同源、共享 localStorage（登录态/主题键互通），但两者是**两个独立的 Pinia 实例**——在外壳改主题不会即时传导进 iframe；主题请在 **iframe 内**的设置中切换（现状行为，不修）。
- **安全响应头（必须经轨道 B 反代）**：见第 6 节根因说明——反代需把 `X-Frame-Options` 改写为 `SAMEORIGIN`、CSP `connect-src` 追加 `http: https:`，否则 `/eval` 与远程模式不可用（`deploy/caddy-anotherviewer.conf` 已内置；nginx 等价指令见 `docs/deployment.md` 附录）。
- **离线壳需二次加载预热**：SW 的 install 只预缓存壳入口（`/`、`/index.html`、manifest、图标），内容哈希 JS/CSS 在首次受控 fetch 时才入壳缓存——**首次访问后立即断网 reload 会白屏**，第二次在线加载后离线壳才完整可用（SW 设计使然，非缺陷）。
