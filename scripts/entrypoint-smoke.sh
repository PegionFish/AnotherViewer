#!/usr/bin/env bash
# entrypoint-smoke.sh — EntryPoint（图像处理副武器）部署后冒烟。
#
# 用法（141 本机或任何能达 9800 的机器）：
#   EP_TOKEN=$(sudo sed -n 's/^token = "\(.*\)"$/\1/p' /server/EntryPoint/config/app.toml) \
#     EP_BASE=http://192.168.6.141:9800 scripts/entrypoint-smoke.sh
#
# token 只经环境变量注入——严禁写进任何文件/仓库（手册 D20）。
# 全链：capabilities → 合成 PNG 上传提交 remove_bg → 轮询终态 → 产物下载校验 PNG 签名。
set -euo pipefail

BASE="${EP_BASE:-http://127.0.0.1:9800}"
TOKEN="${EP_TOKEN:?EP_TOKEN is required (see header comment)}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

auth=(-H "Authorization: Bearer $TOKEN")
fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

echo "[1/5] capabilities"
CAPS=$(curl -fsS -m 15 "${auth[@]}" "$BASE/api/v1/capabilities")
echo "$CAPS" | grep -q '"remove_bg"' || fail "remove_bg capability missing"

echo "[2/5] submit rembg/remove_bg"
python3 - "$TMP/in.png" <<'EOF'
import zlib, struct, sys
def chunk(t, d):
    c = t + d
    return struct.pack('>I', len(d)) + c + struct.pack('>I', zlib.crc32(c))
w = h = 64
raw = b''.join(b'\x00' + bytes([200, 40, 60] * w) for _ in range(h))
png = (b'\x89PNG\r\n\x1a\n'
       + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
open(sys.argv[1], 'wb').write(png)
EOF
SUBMIT=$(curl -fsS -m 120 "${auth[@]}" -F "file @$TMP/in.png" "$BASE/api/v1/inference/rembg/remove_bg")
TID=$(echo "$SUBMIT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["task_id"])')
[ -n "$TID" ] || fail "no task_id in: $SUBMIT"
echo "    task_id=$TID"

echo "[3/5] poll until terminal (≤120s)"
STATUS=""
for _ in $(seq 1 40); do
  sleep 3
  RES=$(curl -fsS -m 15 "${auth[@]}" "$BASE/api/v1/inference/result/$TID")
  STATUS=$(echo "$RES" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  case "$STATUS" in
    completed|failed|cancelled) break ;;
  esac
done
[ "$STATUS" = "completed" ] || fail "terminal status=$STATUS (want completed): ${RES:-<none>}"

echo "[4/5] download artifact (follow 302)"
OUT="$TMP/out.png"
URL=$(echo "$RES" | python3 -c 'import json,sys
outs = json.load(sys.stdin)["outputs"]
print(next((o["url"] for o in outs if o["node_id"] == "output"), outs[0]["url"]))')
curl -fsSL -m 300 -o "$OUT" "$BASE$URL"

echo "[5/5] verify PNG signature"
python3 - "$OUT" <<'EOF'
import sys
d = open(sys.argv[1], 'rb').read(8)
assert d == b'\x89PNG\r\n\x1a\n', f'not a PNG: {d!r}'
EOF

echo "SMOKE OK (task=$TID)"
