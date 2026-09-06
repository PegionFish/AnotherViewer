#!/usr/bin/env bash
# ===========================================================================
# gen-lan-cert.sh —— mkcert 生成 LAN 内网 HTTPS（PWA 轨道 B）本地信任证书
#
# 产物为 PEM（证书 + 私钥），Caddy `tls` 指令可直接使用：
#   deploy/certs/anotherviewer-lan.pem       证书
#   deploy/certs/anotherviewer-lan-key.pem   私钥（chmod 600，勿入库）
#
# 用法：
#   ./scripts/gen-lan-cert.sh                    # 缺省签发 192.168.6.141
#   ./scripts/gen-lan-cert.sh 10.0.0.5 my.host.lan   # 可选参数覆盖 IP/域名列表
#   MKCERT_DRYRUN=1 ./scripts/gen-lan-cert.sh    # 只打印将执行的命令，不生成
#
# 根 CA：mkcert 首次运行在 $(mkcert -CAROOT) 下自动创建 rootCA.pem /
# rootCA-key.pem；各客户端（浏览器/手机）需安装该 rootCA.pem 才信任签发的
# 证书（见 docs/pwa-install.md 第 4 节）。deploy/certs/ 已加入 .gitignore。
# 后续步骤见 docs/pwa-install.md 与 docs/deployment.md（HTTPS 轨道 B 一节）。
# ===========================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CERT_DIR="deploy/certs"
CERT_FILE="$CERT_DIR/anotherviewer-lan.pem"
KEY_FILE="$CERT_DIR/anotherviewer-lan-key.pem"

hosts=("$@")
if [ ${#hosts[@]} -eq 0 ]; then
  hosts=(192.168.6.141)
fi

if ! command -v mkcert >/dev/null 2>&1; then
  cat >&2 <<'EOF'
错误：未找到 mkcert，请先安装：

  Arch Linux:      sudo pacman -S mkcert nss
  Debian/Ubuntu:   sudo apt install mkcert libnss3-tools
                   （仓库无 mkcert 时，从 https://github.com/FiloSottile/mkcert/releases
                     下载对应平台二进制放到 PATH）
  Windows:         choco install mkcert   或   scoop install mkcert
                   （或从上面 GitHub Releases 下载 mkcert.exe）
  macOS:           brew install mkcert

安装后重试本脚本。
EOF
  exit 1
fi

if [ "${MKCERT_DRYRUN:-0}" = "1" ]; then
  echo "[dry-run] 将执行：mkdir -p $CERT_DIR"
  echo "[dry-run] 将执行：mkcert -cert-file $CERT_FILE -key-file $KEY_FILE ${hosts[*]}"
  echo "[dry-run] 将执行：chmod 600 $KEY_FILE"
  echo "[dry-run] 未生成任何文件。"
  exit 0
fi

mkdir -p "$CERT_DIR"
echo "生成证书（SAN: ${hosts[*]}）……"
mkcert -cert-file "$CERT_FILE" -key-file "$KEY_FILE" "${hosts[@]}"
chmod 600 "$KEY_FILE"

CAROOT="$(mkcert -CAROOT)"
cat <<EOF

完成：
  证书：$CERT_FILE
  私钥：$KEY_FILE（600）
  根 CA 目录：$CAROOT（rootCA.pem 分发到各客户端安装，见 docs/pwa-install.md 第 4 节）

后续步骤：
  1. 把 $CAROOT/rootCA.pem 拷到各客户端并安装（安装后重启浏览器）；
  2. 用 deploy/caddy-anotherviewer.conf 起反代（:8443 -> 127.0.0.1:8081），
     步骤见 docs/deployment.md「HTTPS 轨道 B（LAN 内网 HTTPS 反代）」；
  3. 客户端访问 https://${hosts[0]}:8443（详见 docs/pwa-install.md）。
EOF
