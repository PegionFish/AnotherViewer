# 部署指南

## 系统要求

- Java 21+
- Node.js 18+ (构建前端)
- 2GB+ RAM
- 10GB+ 磁盘空间

## 分发形态

官方发布产物为 **zip 包**（GitHub Releases）：`anotherviewer-<version>-<os>-<arch>.zip`，由 `scripts/package.sh` 生成。结构：

```
lib/app.jar        # 后端可执行 jar（含前端静态资源）
bin/start.sh       # 启动（脚本目录推导，--data-dir 透传）
bin/stop.sh
data/              # 数据目录模板（固定结构，见下）
README.txt
```

安装即解压；**依赖系统 Java 21**（不含 JRE）。发行版包管理器（deb/rpm）的预适配骨架在 `packaging/`（见 `packaging/README.md`）。

### 数据目录（data-dir）

服务器唯一的权威数据目录，由 `--data-dir` 参数或 `ANOTHERVIEWER_DATA_DIR` 环境变量指定（默认 `./data`）。固定结构：

```
<data-dir>/
├── anotherviewer.db        # SQLite（同步实体、用户配置 server_config 等全部数据）
├── security.key       # token/密码加密密钥
├── downloads/         # 下载内容默认位置（可用管理界面改到其他路径，持久化）
├── cache/             # 图片缓存
├── backups/           # 备份产物落点
└── logs/              # Tomcat access log（access.yyyy-MM-dd.log，按天滚动，保留 30 天）
```

下载路径与缓存路径可在管理界面单独设置（持久化于 `server_config` 表，重启不丢）；其余路径一律由 data-dir 派生。迁移/备份基于此固定结构，**与具体路径无关**。

## 快速启动（zip）

```bash
unzip anotherviewer-<version>-*.zip -d ~/anotherviewer
cd ~/anotherviewer
./bin/start.sh                 # 默认 data-dir = ./data
./bin/start.sh --data-dir=/srv/anotherviewer   # 自定义数据目录（docker 挂载卷的等价物）
./bin/stop.sh
```

systemd 服务（可选）：`packaging/systemd/anotherviewer.service.tpl`（经 `Environment=ANOTHERVIEWER_DATA_DIR=/var/lib/anotherviewer` 注入数据目录；裸 `--data-dir` 不是 Spring 配置键，命令行等价写法为 `--anotherviewer.data-dir=...`）。

## Docker 部署

### 前置条件

- Docker 20.10+
- Docker Compose 2.0+

### 步骤

1. 克隆仓库
```bash
git clone https://github.com/PegionFish/AnotherViewer.git
cd AnotherViewer
```

2. 构建
```bash
./build.sh
```

3. 启动
```bash
docker compose up -d
```

4. 访问
打开浏览器访问 `http://localhost:8080`

### 数据持久化

Docker Compose 会将以下目录挂载到宿主机：

- `./data` - 数据库文件
- `./cache` - 图片缓存
- `./downloads` - 下载文件

### 自定义配置

通过环境变量覆盖默认配置：

```yaml
# docker-compose.yml
services:
  anotherviewer:
    environment:
      - ANOTHERVIEWER_SERVER_PORT=9090
      - ANOTHERVIEWER_CACHE_SIZE_MB=20480
```

## 裸机部署

### 前置条件

- Java 21+
- Node.js 18+ (构建前端)

### 步骤

1. 克隆仓库
```bash
git clone https://github.com/PegionFish/AnotherViewer.git
cd AnotherViewer
```

2. 构建
```bash
./build.sh
```

3. 启动
```bash
./start.sh
```

4. 后台运行（可选）
```bash
nohup ./start.sh > anotherviewer.log 2>&1 &
```

### systemd 服务（可选）

创建 `/etc/systemd/system/anotherviewer.service`:

```ini
[Unit]
Description=AnotherViewer Web
After=network.target

[Service]
Type=simple
User=anotherviewer
WorkingDirectory=/opt/anotherviewer
# JAR 用绝对路径，不能用通配符（systemd 不展开 glob）
ExecStart=/usr/bin/java -jar /opt/anotherviewer/anotherviewer-web.jar --server.port=8080
# 数据目录唯一入口：db/security.key/downloads/cache/backups 均由其派生
Environment=ANOTHERVIEWER_DATA_DIR=/opt/anotherviewer/data
Restart=always
RestartSec=10
ReadWritePaths=/opt/anotherviewer/data

[Install]
WantedBy=multi-user.target
```

启用服务：
```bash
sudo systemctl daemon-reload
sudo systemctl enable anotherviewer
sudo systemctl start anotherviewer
```

## 网络存储接入

### CIFS/SMB

```bash
# /etc/fstab
//192.168.6.141/media  /data/anotherviewer/downloads  cifs  credentials=/etc/smb-cred,uid=1000,gid=1000,iocharset=utf8  0  0
```

### NFS

```bash
# /etc/fstab
192.168.6.141:/volume1/media  /data/anotherviewer/downloads  nfs  defaults,soft,timeo=10,retrans=3  0  0
```

### rclone (云存储)

```bash
rclone mount cloud:/data /data/anotherviewer/downloads --vfs-cache-mode full --vfs-cache-max-size 1G
```

## 日志持久化（journald）

systemd 部署下应用日志由 journald 收管（`journalctl -u anotherviewer -f`）。journald 默认 `Storage=auto`：只有 `/var/log/journal` 已存在才落盘，否则只写内存盘 `/run/log/journal`，**主机重启后日志全部丢失**。两种开启方式任选其一：

**首选：journald 配置 drop-in**（自包含——persistent 模式下 journald 自动创建 `/var/log/journal`，无需手工建目录）：

```bash
sudo mkdir -p /etc/systemd/journald.conf.d
sudo tee /etc/systemd/journald.conf.d/99-persistent.conf >/dev/null <<'EOF'
[Journal]
Storage=persistent
EOF
sudo systemctl restart systemd-journald
```

**备选（等价）**：只手工创建目录，默认的 `Storage=auto` 检测到目录存在即自动转持久化：

```bash
sudo mkdir -p /var/log/journal
sudo systemctl restart systemd-journald
```

> 注意：`Storage=` 是 `journald.conf`（`[Journal]` 段）的选项，**不是 unit 属性**——写进 `systemctl edit anotherviewer` 生成的 unit drop-in（`anotherviewer.service.d/override.conf`）会被 systemd 以 "Unknown key" 忽略，不生效。

验证：`ls /var/log/journal/<machine-id>/` 出现归档文件；重启服务或主机后 `journalctl -b -1` 仍能回看上一次启动的日志。

应用 access log（见下节）是应用直接写的文件，不经 journald，不受此设置影响。

## 慢请求分析（access log）

应用对每个 HTTP 请求记一行访问日志（`server.tomcat.accesslog`，默认开启）：落 `<data-dir>/logs/access.yyyy-MM-dd.log`，按天滚动，保留 30 天自动清理。字段最小集：时间、客户端 IP、请求行（方法 路径 协议）、状态码、响应字节、耗时毫秒：

```
[06/Sep/2026:10:01:02 +0800] 192.168.6.10 "GET /api/v1/health HTTP/1.1" 200 143 12
```

一行内空格分隔，**最后一列（`$NF`）即耗时毫秒（%D）**。SPA 前端静态资源（`/`、`/assets/**`）与 API 共用同一份日志；分析 API 时按 `/api/v1/` 前缀过滤（请求路径固定在第 5 列）。

常用命令（在 `<data-dir>/logs` 目录下执行）：

```bash
# Top 20 最慢的 API 请求：%D 随行输出 → 按数值排序 → 还原原始行
grep -h '/api/v1/' access.*.log | awk '{print $NF, $0}' | sort -n | cut -d' ' -f2- | tail -n 20

# 只看超过 500ms 的 API 请求
awk '$5 ~ /^\/api\/v1\// && $NF > 500' access.*.log

# API 耗时分布：请求数 / 平均 / p50 / p95 / 最大（毫秒）
grep -h '/api/v1/' access.*.log | awk '{print $NF}' | sort -n \
  | awk '{a[NR]=$1; s+=$1} END {if (NR==0) {print "no requests"; exit} print "requests="NR, "avg=" int(s/NR) "ms", "p50=" a[int((NR-1)*0.5)+1] "ms", "p95=" a[int((NR-1)*0.95)+1] "ms", "max=" a[NR] "ms"}'

# 对照组：前端静态资源访问（排除 API）
grep -hv '/api/v1/' access.*.log | tail -n 20
```

> data-dir 传**绝对路径**时 access log 一定落 `<data-dir>/logs`；若传相对路径（如裸 `./gradlew bootRun` 的默认 `./data`），Tomcat 会把它解析到自己的临时 basedir（`/tmp/tomcat.<port>.<随机>`）而非工作目录。官方 zip 包 `bin/start.sh` 与 `scripts/dev-run.sh` 均使用绝对路径，不受影响。

## 故障排查

### 端口被占用

```bash
# 查找占用端口的进程
lsof -i :8080

# 杀死进程
kill -9 <PID>
```

### 数据库错误

```bash
# 删除数据库重新创建
rm data/anotherviewer.db
# 重启服务
```

### 权限问题

```bash
# 确保数据目录权限正确（用户与 systemd unit 的 User= 一致）
chown -R anotherviewer:anotherviewer /opt/anotherviewer/data
chmod -R u+rwX /opt/anotherviewer/data
```

## 备份 / 还原 / 迁移

备份与还原在管理界面「备份」页操作（`GET /api/v1/backup/export`、`POST /api/v1/backup/restore`），产物格式见 `contracts/backup-format.md`。

- **备份**：固定结构打包（db + security.key + server_config；下载内容默认排除、可选包含）。分片是独立 7z 文件 + manifest，可单独拷到 NAS/U 盘异地备份。还原前旧文件保留 `.bak`，需确认词 `RESTORE` + 重启生效
- **迁移（换机/换目录）**：三种等价途径——
  1. 备份（元数据，≤50MB）→ 新机器 WebUI 还原
  2. 含下载内容的大备份（GB 级）→ 手动解包分片/直接拷贝 data-dir（**WebUI 上传限 50MB**，面向元数据）
  3. 直接拷贝整个 data-dir（结构固定，目标路径可以不同）
- **App 包名迁移（com.xjs.anotherviewer → com.pf.anotherviewer）**：旧包名 app 覆盖安装 legacy 包（`-PapplicationId=com.xjs.anotherviewer`）→ 手动同步推数据到服务器 → 新包名 app 配对拉全量；下载文件留在原存储位置，新包名 app 重新授权 SAF 目录即复用

### 从原版 EhViewer 迁移

原版 EhViewer 的本地数据可经「导出数据」得到 `.db` 文件；迁移有两条路径——路径 1 导入本 App 后经同步推上服务器，路径 2 由 WebUI 导入端点直接入库，完成跨 app 迁移。

**迁移路径**（两条路径，都要求旧设备先经「导出数据」得到 `.db`）：

**路径 1（legacy 包覆盖安装，零代码）**：

1. 旧设备：原版 EhViewer「设置 → 高级 → 导出数据」，得到 `yyyy-MM-dd-HH-mm-ss-SSS.db`（导出到外置存储）
2. 拷贝该 `.db` 到新机
3. 用 **legacy 包**（`-PapplicationId=com.xjs.anotherviewer`，见 应用标识 词条）覆盖安装——legacy applicationId 覆盖安装后 app 内部数据目录原样保留，`okhttp3-cookie.db`（含 `ipb_member_id` / `ipb_pass_hash` / `igneous`）与 shared_prefs 随包迁移
4. App「设置 → 高级 → 导入数据」，选择该 `.db`
5. 手动触发同步，把数据推上 WebUI 服务器
6. 新包名 app（`com.pf.anotherviewer`）配对后拉全量；下载文件留在原存储位置，重新授权 SAF 目录即复用

**路径 2（WebUI 导入端点）**：

- 管理界面「备份」页或直接调用 `POST /api/v1/backup/import-ehviewer`，multipart 上传 legacy EhViewer `.db`（可选 `cookies` 字段）
- **表驱动扫描**：以 `PRAGMA table_info` 感知上传库实际存在的表与列集，缺列落默认值；8 张核心业务表映射到 sync 实体，`Black_List` → black_list，`Gallery_Tags` → gallery_tags，`DOWNLOAD_DIRNAME` → download_dirname；gid 冲突默认跳过（计 skipped），`force=true` 时 upsert
- **可选 cookies**：上传 `okhttp3-cookie.db`（`OK_HTTP_3_COOKIE` 表）或 JSON cookie 数组；仅收容站点域（`e-hentai.org` / `exhentai.org` / `ehgt.org` / `forums.e-hentai.org` 及子域）写入 `SiteSessionManager.cookieStore`，使 WebUI 代理可带登录态抓取 EX 站；非站点域 cookie 忽略；cookie 同时作为 ehSession 同步实体加密落库（见下）
- **语义**：登录态为同步实体 **ehSession**（EH 登录会话 + 设置，双端 LWW 双向同步，登出=tombstone 传播；Web 端 cookie 加密落库 `enc:v1:` + security.key，进程内解密，重启后从库恢复）——见 ADR-0004；旧决策「登录态不进 sync 实体；cookieStore 会话级、重启即失效」已推翻。端点为管理员鉴权接受（Bearer token，同其余 `/api`）

**导入语义（importDB）**：

- importDB 自动把 v7 库升级到 v8：补齐同步元数据列，进度字段落 0，下载记录 STATE 保留
- 迁移的数据 = 下载记录、下载目录名台账、历史、本地收藏、书签、过滤、快速搜索、下载标签
- 偏好不迁移；黑名单 / 画廊标签随 legacy db 迁移被导入（黑名单→WebUI black_list，画廊标签→gallery_tags）

**登录授权说明**：

- 导出的 `.db` 文件**不含登录授权（cookies）**；登录态（`ipb_member_id` / `ipb_pass_hash` / `igneous` 等）保存在 app 内部数据目录的 `okhttp3-cookie.db` 与 shared_prefs
- 覆盖安装 legacy 包时，登录态随应用数据目录原样保留，**无需额外操作**
- 导入 `.db` 只迁业务表，不影响已保留的登录态

## HTTPS 轨道 B（LAN 内网 HTTPS 反代）

PWA 安装层（Service Worker、浏览器「安装」入口）要求**安全上下文**。生产实例 `http://192.168.6.141:8081`（systemd 服务 `anotherviewer-web`，jar 在 `/server/AnotherViewer/lib/app.jar`）保持原样不动——Android App 与既有 HTTP 客户端零扰动；另用 mkcert 本地证书 + 反代在 **:8443** 提供 HTTPS。客户端视角与安装步骤见 `docs/pwa-install.md`。

> 与 `deploy/Caddyfile` 的分工：那份面向公网域名（Caddy 自动获取 Let's Encrypt 证书，监听 443）；本节用的是 `deploy/caddy-anotherviewer.conf`（LAN 无域名，mkcert 证书，监听 8443），二选一。

### 0. 安全响应头改写（PWA 必需，conf 已内置）

后端 `SecurityConfig.kt`（M-4 加固）对文档响应固定发 `X-Frame-Options: DENY` 与 `Content-Security-Policy: … connect-src 'self' …`。两者会分别废掉 PWA 两个能力：**`/eval` 比例评估台**（DENY 连同源 iframe 也拒显）与**远程模式 + `/setup` 跨主机探活**（`connect-src 'self'` 在浏览器层拦截跨源 fetch，CORS 配置再对也无效，INT-4 实测）。这两处属平台代码，按红线记录不改——由本轨道的反代在代理层覆盖响应头做两处**最小放宽**（`deploy/caddy-anotherviewer.conf` 的 `header` 块已内置）：

- `X-Frame-Options: DENY` → `SAMEORIGIN`（仅放开同源 framing，`/eval` 的 iframe 与外壳同源，够用）；
- CSP 原策略逐项保留，`connect-src` 由 `'self' ws: wss:` 追加为 `'self' http: https: ws: wss:`（放行任意 LAN serverBase；威胁模型=私有网络单用户，与 `ANOTHERVIEWER_CORS_ORIGINS=*` 同一取决策）。

回滚：删掉 conf 里的 `header` 块 reload 即恢复后端原头。若将来后端自行放宽这两处（记录在案的建议：`frameOptions().sameOrigin()` + CSP `connect-src` 追加 `http: https:`），删掉反代改写即可，双层不会打架。

### 1. 生成证书（mkcert）

141 上安装 mkcert（Arch：`sudo pacman -S mkcert nss`；Debian/Ubuntu：`sudo apt install mkcert libnss3-tools`；Windows/macOS 见 `scripts/gen-lan-cert.sh` 报错提示），然后：

```bash
cd /server/AnotherViewer
./scripts/gen-lan-cert.sh          # 缺省签发 192.168.6.141；可传参覆盖，如 ./scripts/gen-lan-cert.sh 192.168.6.141 av.lan
```

产物（PEM，Caddy 可直接用；`deploy/certs/` 已在 `.gitignore`，私钥不入库）：

- `deploy/certs/anotherviewer-lan.pem` — 证书
- `deploy/certs/anotherviewer-lan-key.pem` — 私钥（脚本已 `chmod 600`）
- 根 CA：`$(mkcert -CAROOT)/rootCA.pem`，分发给各客户端安装（见 `docs/pwa-install.md` 第 4 节）

可选：在 141 上执行一次 `mkcert -install`，让 141 本机的 curl/浏览器也信任该根 CA（只影响 141 本机，其他客户端仍需各自安装 rootCA.pem）。

### 2. 安装 / 配置 Caddy（141）

安装 Caddy（Arch：`sudo pacman -S caddy`；Debian/Ubuntu：`sudo apt install caddy`）。

**方式 A：独立 systemd 实例**（不影响发行版 caddy 服务，推荐）：

```bash
sudo tee /etc/systemd/system/caddy-anotherviewer.service >/dev/null <<'EOF'
[Unit]
Description=Caddy (AnotherViewer LAN HTTPS 8443)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/server/AnotherViewer
ExecStart=/usr/bin/caddy run --config /server/AnotherViewer/deploy/caddy-anotherviewer.conf
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now caddy-anotherviewer
journalctl -u caddy-anotherviewer -f
```

> `tls` 的相对路径由 caddy 按工作目录解析，上面的 unit 已把 `WorkingDirectory` 固定到 `/server/AnotherViewer`；更稳妥的做法是把 `deploy/caddy-anotherviewer.conf` 中 `tls` 两行改成绝对路径。

**方式 B：手工前台命令**（调试用）：

```bash
cd /server/AnotherViewer
caddy run --config deploy/caddy-anotherviewer.conf
```

### 附录：nginx 等价 server 块

不用 Caddy 时，以下 nginx 配置等价（`map` 需在 `http{}` 上下文；发行版布局放进 `/etc/nginx/conf.d/anotherviewer-lan.conf` 即可）：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 8443 ssl;
    server_name 192.168.6.141;

    ssl_certificate     /server/AnotherViewer/deploy/certs/anotherviewer-lan.pem;
    ssl_certificate_key /server/AnotherViewer/deploy/certs/anotherviewer-lan-key.pem;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        # WebSocket 升级（/ws SockJS）
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        # 安全响应头改写（PWA 必需，见「0. 安全响应头改写」；与 Caddy conf 的 header 块等价）
        proxy_hide_header X-Frame-Options;
        add_header X-Frame-Options SAMEORIGIN always;
        proxy_hide_header Content-Security-Policy;
        add_header Content-Security-Policy "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' http: https: ws: wss:; font-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'" always;
    }
}
```

### 3. 证书续期

mkcert 签发的证书有效期 825 天（约 27 个月）。到期特征：客户端浏览器报 `NET::ERR_CERT_DATE_INVALID`；141 上可随时查看：

```bash
openssl x509 -in /server/AnotherViewer/deploy/certs/anotherviewer-lan.pem -noout -enddate
```

重生成三步（**客户端无需重装根 CA**——根 CA 未变，只是同一根重新签发叶子证书）：

```bash
cd /server/AnotherViewer
./scripts/gen-lan-cert.sh
sudo systemctl restart caddy-anotherviewer
```

> 例外：若 141 重装系统或 `mkcert -CAROOT` 目录被删导致**根 CA 重建**，则四端客户端需重新安装新的 rootCA.pem。

### 4. 验证与回滚

```bash
# 经 8443 反代验证（-k：curl 不信任 mkcert 根 CA；141 上做过 mkcert -install 后可去掉 -k）
curl -sk https://localhost:8443/api/v1/health

# 直连 8081 对照：行为零变化
curl -s http://127.0.0.1:8081/api/v1/health
```

回滚：`sudo systemctl disable --now caddy-anotherviewer`（nginx 等价：删掉上面 server 块后 reload）。8081 全程未被改动，随时可停反代，Android App 与既有 HTTP 客户端零扰动。

## 远程模式 CORS/WS env

外壳（`https://192.168.6.141:8443`，Caddy/8443）与数据服务器（`http://192.168.6.141:8081`）不同源，即**远程模式**：API 与图片请求经 serverBase 指向 8081，浏览器会发起跨域请求，需后端 CORS 放行。后端零代码改动，全部经环境变量控制。

### ANOTHERVIEWER_CORS_ORIGINS

- 默认仅回环：`http://localhost:*,http://127.0.0.1:*`；逗号分隔列表，`allowedOriginPatterns` 通配匹配，支持 `*` 且 `allowCredentials=true`；**只映射 `/api/**`**。
- 远程模式需放行外壳 origin（推荐精确列出；`*` 会放行 LAN 内任意网页，仅可信内网使用）：

```bash
sudo systemctl edit anotherviewer-web
# 编辑器中写入：
#   [Service]
#   Environment=ANOTHERVIEWER_CORS_ORIGINS=http://192.168.6.141:8443
# 可信内网偷懒写法：Environment=ANOTHERVIEWER_CORS_ORIGINS=*
```

非交互等价写法与生效：

```bash
sudo mkdir -p /etc/systemd/system/anotherviewer-web.service.d
printf '[Service]\nEnvironment=ANOTHERVIEWER_CORS_ORIGINS=*\n' \
  | sudo tee /etc/systemd/system/anotherviewer-web.service.d/10-cors.conf >/dev/null
sudo systemctl daemon-reload
sudo systemctl restart anotherviewer-web
```

**回滚 = 删 drop-in**（恢复默认仅回环）：

```bash
sudo rm /etc/systemd/system/anotherviewer-web.service.d/10-cors.conf
sudo systemctl daemon-reload && sudo systemctl restart anotherviewer-web
```

### ANOTHERVIEWER_WS_ORIGINS

WebSocket/SockJS 端点（`/ws`）的来源校验，默认 `*`——远程模式**通常无需设置**；仅当要收紧 WS 来源时才按上面同样的 drop-in 方式配置（如 `Environment=ANOTHERVIEWER_WS_ORIGINS=https://192.168.6.141:8443`，改完同样 `daemon-reload` + `restart`）。

### 排障提示

浏览器 DevTools console 中区分两类失败：

- **API 请求报 CORS 错**（`Access-Control-Allow-Origin` 缺失/不匹配，对象是 `/api/v1/...` 的 fetch/XHR）→ 查 `ANOTHERVIEWER_CORS_ORIGINS` 是否放行了外壳 origin。
- **SockJS 握手失败 / WebSocket 连接失败**（`/ws` 相关，`info` 请求或 `WebSocket connection to ... failed`）→ 查 `ANOTHERVIEWER_WS_ORIGINS`（默认 `*` 一般不会失败；仅收紧后需检查）。
