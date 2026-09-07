#!/usr/bin/env python3
"""
API 打码覆盖审计脚本（只读，GET-only）。

用途：遍历 WebUI 的 GET 端点，校验响应中的敏感字段是否符合「内容打码模式」
的脱敏形态，找出仍会输出完整标题/标签/评论/路径的端点。

安全设计：
  - 只发 GET 请求（POST/PUT/DELETE 一律不碰——维护清理、下载操作等有副作用）；
  - 绝不打印响应的原始字符串值，只输出端点、字段名、判定与长度/计数；
  - 不写任何临时文件。

用法：
  python3 scripts/audit-api-masking.py [--base http://192.168.6.141:8081] [--token XXX]

判定规则（对 JSON 树递归）：
  title      非空且不是 #<数字>            → LEAK:title
  titleJpn / uploader / galleryUrl 非空     → LEAK:<field>
  comment    非空（评论对象：同含 time+score）→ LEAK:comment
  simpleTags / tags 非空数组                → LEAK:tags
  path       长度 >10 且同对象有 sizeBytes   → LEAK:path
  downloadDir 非空（下载列表的绝对路径，目录名含标题）→ LEAK:downloadDir
  value      非空且不是 #<数字>（排行榜行，同含 href 键）→ LEAK:toplist-value
  href       非空（排行榜行）               → LEAK:toplist-href

刻意不判定的（按设计保留真实数据/无内容）：/sync/**（App 全量）、
/settings 与 /smb/config 与 /preferences（配置往返）、/image/**（图片字节）、
/backup/export*（全库 zip）、favoriteName（用户自起名）。
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

MASKED_TITLE = re.compile(r"^#\d+$")
GID_RE = re.compile(r"/g/(\d+)")

# ── 探测清单：method, path（含占位符）, 说明 ──────────────────────────────
# 占位符 {gid} {token} {dlid} {jobid} {taskid} 由引导步骤填充，缺资源则 SKIP。
PROBES = [
    ("GET", "/api/v1/privacy/mask",                    "打码开关状态（无内容，自检）"),
    ("GET", "/api/v1/gallery/search?keyword=",         "站点搜索（空关键词=本地快路径）"),
    ("GET", "/api/v1/gallery/search?keyword=a&page=0", "站点搜索（带关键词）"),
    ("GET", "/api/v1/gallery/feed?mode=toplist",       "排行榜"),
    ("GET", "/api/v1/gallery/feed?mode=subscription",  "订阅 feed"),
    ("GET", "/api/v1/gallery/feed?mode=popular",       "热门 feed"),
    ("GET", "/api/v1/gallery/quick-search",            "快速搜索预设"),
    ("GET", "/api/v1/gallery/{gid}?token={token}",     "画廊详情"),
    ("GET", "/api/v1/history/list?page=0",             "历史列表（history 视图）"),
    ("GET", "/api/v1/favorite/list",                   "收藏（favorite 视图）"),
    ("GET", "/api/v1/comment/list/{gid}",              "评论列表"),
    ("GET", "/api/v1/download/list",                   "下载列表"),
    ("GET", "/api/v1/download/info/{dlid}",            "下载行详情"),
    ("GET", "/api/v1/download/slots",                  "下载槽位"),
    ("GET", "/api/v1/download/maintenance/preview",    "维护预览（当初的事故端点）"),
    ("GET", "/api/v1/archive/list/{gid}",              "归档列表"),
    ("GET", "/api/v1/torrent/list/{gid}",              "种子列表"),
    ("GET", "/api/v1/jobs/active?type=CACHE_CLEAR",    "活跃任务（需 type 参数）"),
    ("GET", "/api/v1/jobs/{jobid}",                    "任务详情"),
    ("GET", "/api/v1/process/tasks",                   "处理任务列表"),
    ("GET", "/api/v1/process/history",                 "处理历史"),
    ("GET", "/api/v1/process/status/{taskid}",         "处理任务状态"),
    ("GET", "/api/v1/cache/stats",                     "缓存统计（无内容）"),
    ("GET", "/api/v1/image/cache/status",              "图源缓存统计（无内容）"),
    ("GET", "/api/v1/site/availability",               "站点可用性（无内容）"),
    ("GET", "/api/v1/backup/state",                    "备份状态（无内容）"),
]

# 按设计不探测/不判定的端点，只在报告中说明
BY_DESIGN = [
    "/api/v1/sync/pull、/sync/status、/sync/devices —— App 同步，刻意全量（打码不覆盖）",
    "/api/v1/settings、/api/v1/smb/config、/api/v1/preferences —— 配置往返，需完整回显",
    "/api/v1/backup/export* —— 全库 zip（二进制，含全部标题），开码期间勿导出",
    "/api/v1/image/** —— 图片字节按设计保留真实访问",
    "所有 POST/PUT/DELETE —— 有副作用，本脚本不碰",
]


def http_json(base, path, token, timeout):
    url = base + path
    req = urllib.request.Request(url, method="GET")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            ctype = resp.headers.get("Content-Type", "")
            return resp.status, ctype, raw
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), e.read()
    except Exception as e:  # noqa: BLE001
        return None, "transport", str(e).encode()


def walk_violations(node, found, prefix=""):
    """递归校验；found 收集 (字段名, 位置) —— 绝不收集值。"""
    if isinstance(node, dict):
        title = node.get("title")
        if isinstance(title, str) and title and not MASKED_TITLE.match(title):
            found.append(f"{prefix or '/'}title")
        for f in ("titleJpn", "uploader", "galleryUrl"):
            v = node.get(f)
            if isinstance(v, str) and v:
                found.append(f"{prefix or '/'}{f}")
        comment = node.get("comment")
        if isinstance(comment, str) and comment and "time" in node and "score" in node:
            found.append(f"{prefix or '/'}comment")
        for f in ("simpleTags", "tags"):
            v = node.get(f)
            if isinstance(v, list) and v:
                found.append(f"{prefix or '/'}{f}")
        path = node.get("path")
        if isinstance(path, str) and len(path) > 10 and "sizeBytes" in node:
            found.append(f"{prefix or '/'}path")
        dd = node.get("downloadDir")
        if isinstance(dd, str) and dd:
            found.append(f"{prefix or '/'}downloadDir")
        # 排行榜行：value/href 组合
        if "value" in node and "href" in node:
            v = node.get("value")
            if isinstance(v, str) and v and not MASKED_TITLE.match(v):
                found.append(f"{prefix or '/'}toplist-value")
            h = node.get("href")
            if isinstance(h, str) and h:
                found.append(f"{prefix or '/'}toplist-href")
        for k, v in node.items():
            if isinstance(v, (dict, list)):
                walk_violations(v, found, f"{prefix}{k}/")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            if isinstance(v, (dict, list)):
                walk_violations(v, found, prefix)


def main():
    ap = argparse.ArgumentParser(description="打码覆盖审计（GET-only，不打印原始内容）")
    ap.add_argument("--base", default="http://192.168.6.141:8081", help="服务根地址")
    ap.add_argument("--token", default="", help="Bearer token（LAN 免鉴权部署可省略）")
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--force", action="store_true", help="打码关闭时仍然继续审计")
    args = ap.parse_args()

    base = args.base.rstrip("/")

    # 0. 打码开关自检——关闭时脱敏失效，审计结果无意义
    status, _, raw = http_json(base, "/api/v1/privacy/mask", args.token, args.timeout)
    try:
        mask_on = json.loads(raw).get("enabled") is True
    except Exception:  # noqa: BLE001
        mask_on = False
    if not mask_on and not args.force:
        print("打码开关当前为关闭（/privacy/mask enabled=false）。")
        print("此状态下全量输出是预期行为，审计无意义；请先在管理面板开启，或加 --force。")
        sys.exit(2)
    print(f"打码开关：{'开启' if mask_on else '关闭（--force）'}\n")

    # 1. 引导：取样本 gid / token / dlid / jobid / taskid（脱敏响应仍含这些 ID）。
    #    gid/token 从 /history/list 取（/gallery/history 无 GET 路由）；
    #    历史为空时再试 /favorite/list。
    gid = token_value = dlid = jobid = taskid = None
    for boot_path in ("/api/v1/history/list?page=0", "/api/v1/favorite/list"):
        status, _, raw = http_json(base, boot_path, args.token, args.timeout)
        try:
            data = json.loads(raw)
            rows = data.get("history") or data.get("data") or []
            if rows:
                gid, token_value = rows[0].get("gid"), rows[0].get("token")
                break
        except Exception:  # noqa: BLE001
            continue
    status, _, raw = http_json(base, "/api/v1/download/list", args.token, args.timeout)
    try:
        rows = json.loads(raw)
        if isinstance(rows, dict):
            rows = rows.get("data") or rows.get("list") or []
        if rows:
            dlid = rows[0].get("id")
    except Exception:  # noqa: BLE001
        pass
    status, _, raw = http_json(base, "/api/v1/jobs/active", args.token, args.timeout)
    try:
        data = json.loads(raw)
        jobs = data if isinstance(data, list) else data.get("jobs") or data.get("data") or []
        if jobs:
            jobid = jobs[0].get("jobId") or jobs[0].get("id")
    except Exception:  # noqa: BLE001
        pass
    status, _, raw = http_json(base, "/api/v1/process/tasks", args.token, args.timeout)
    try:
        data = json.loads(raw)
        tasks = data if isinstance(data, list) else data.get("tasks") or data.get("data") or []
        if tasks:
            taskid = tasks[0].get("taskId") or tasks[0].get("id")
    except Exception:  # noqa: BLE001
        pass
    print(f"引导样本：gid={gid} dlid={dlid} jobid={'有' if jobid else '无'} taskid={'有' if taskid else '无'}\n")

    # 2. 逐端点探测
    results = []  # (path, verdict, detail)
    for method, path, desc in PROBES:
        if "{" in path:
            import re as _re
            sample = {"gid": gid, "token": token_value, "dlid": dlid, "jobid": jobid, "taskid": taskid}
            needs = _re.findall(r"\{(\w+)\}", path)
            missing = [k for k in needs if sample.get(k) in (None, "")]
            if missing:
                results.append((path, "SKIP", f"缺少引导样本：{','.join(missing)}（对应列表为空）"))
                continue
            path = path.format(**sample)

        status, ctype, raw = http_json(base, path, args.token, args.timeout)
        if status is None:
            results.append((path, "ERROR", str(raw)[:80]))
            continue
        if status == 404:
            results.append((path, "SKIP", "404（资源不存在：样本 ID 失效或数据为空）"))
            continue
        if status >= 400:
            results.append((path, "SKIP", f"HTTP {status}（无内容体，不计入泄漏判定）"))
            continue
        if "json" not in ctype:
            results.append((path, "SKIP", f"非 JSON（{ctype.split(';')[0]}，{len(raw)}B）——二进制端点按设计不判定"))
            continue
        try:
            data = json.loads(raw)
        except Exception:  # noqa: BLE001
            results.append((path, "SKIP", "JSON 解析失败（原样放行不计入）"))
            continue

        found = []
        walk_violations(data, found)
        if found:
            uniq = sorted(set(f.rsplit("/", 1)[-1] for f in found))
            results.append((path, "LEAK", f"字段：{', '.join(uniq)}（共 {len(found)} 处）"))
        else:
            results.append((path, "OK", "脱敏形态正常"))

    # 3. 报告
    print(f"{'端点':<52} 判定   说明")
    print("-" * 100)
    for path, verdict, detail in results:
        mark = {"OK": "✓", "LEAK": "✗", "SKIP": "－", "ERROR": "!"}.get(verdict, "?")
        print(f"{path:<52} {mark} {verdict:<5} {detail}")

    leaks = [r for r in results if r[1] == "LEAK"]
    print("\n" + "=" * 100)
    if leaks:
        print(f"发现 {len(leaks)} 个疑似泄漏端点（字段名见上表，原始值未打印）：")
        for path, _, detail in leaks:
            print(f"  - {path}  {detail}")
        print("\n处置建议：把对应 DTO 的敏感字段纳入 PrivacyMaskFilter.redact 规则，")
        print("或（无 gid 锚点的自定义形态）在服务层出口做端点级脱敏（参考 topListFeed）。")
    else:
        print("所有探测端点均未发现打码形态违规。")
    print("\n按设计不探测：")
    for line in BY_DESIGN:
        print(f"  - {line}")


if __name__ == "__main__":
    main()
