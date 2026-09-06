#!/usr/bin/env node
/**
 * verify-manifest.mjs — PWA manifest 端到端验证（纯 node，无浏览器依赖）
 *
 * 流程：
 *   1. 缺省：用 node:http 自起静态服务器（root=web-frontend/public/，随机空闲端口）；
 *      设置 BASE 环境变量时不自起，直接对 BASE 拉取（供将来对 141/preview 复用）。
 *   2. GET /manifest.json → 200 且 JSON 可解析。
 *   3. manifest.icons 每一项 src 逐个 GET → 200 且响应字节数 > 0。
 *   4. manifest.shortcuts 每一项 url 逐个 GET → 200。
 *   5. 结构校验：
 *      - 512 图标条目不再含 form_factor 字段（icon 条目不支持该字段，会被浏览器忽略）；
 *      - shortcuts 含 url==='/eval' 且 name==='比例评估'；
 *      - id==='/'、display==='standalone'、orientation==='any' 保持不回归。
 *
 * 静态服务器行为：
 *   - 带扩展名的路径：按文件内容（Content-Type 映射）返回，文件缺失 → 404；
 *   - 无扩展名的路径（SPA 路由，如 /search、/eval）：回退 index.html 内容，
 *     模拟 SPA fallback，使 /eval「可达」可断言（本地 root=public/ 下无 index.html
 *     时回退 web-frontend/index.html，再缺省则用最小占位 HTML）。
 *
 * 退出码：0 = 全部断言通过（打印 "verify-manifest: PASS"），1 = 存在失败。
 *
 * 用法：cd web-frontend && node e2e/verify-manifest.mjs
 *       BASE=http://host:port node e2e/verify-manifest.mjs
 */
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, resolve, sep } from 'node:path'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const E2E_DIR = dirname(fileURLToPath(import.meta.url))
const PUBLIC_ROOT = resolve(E2E_DIR, '..', 'public')
const FALLBACK_INDEX_CANDIDATES = [
  join(PUBLIC_ROOT, 'index.html'),
  resolve(E2E_DIR, '..', 'index.html'),
]

// 常见静态资源 Content-Type 映射
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}

function log(...args) {
  console.log('[verify-manifest]', ...args)
}

/** 解析请求路径 → 磁盘绝对路径；含扩展名返回 { file, mime }，无扩展名返回 { spa: true } */
function resolveRequest(pathname) {
  let decoded
  try {
    decoded = decodeURIComponent(pathname)
  } catch {
    return { bad: true }
  }
  if (decoded === '/' || decoded === '') return { spa: true }
  const abs = resolve(PUBLIC_ROOT, '.' + decoded)
  // 防目录穿越：必须仍位于 PUBLIC_ROOT 内
  if (abs !== PUBLIC_ROOT && !abs.startsWith(PUBLIC_ROOT + sep)) return { bad: true }
  const ext = extname(abs)
  if (ext) return { file: abs, mime: MIME[ext.toLowerCase()] || 'application/octet-stream' }
  return { spa: true }
}

/** 自起静态服务器（随机空闲端口），返回 { server, origin } */
async function startServer() {
  let fallbackCache
  const loadFallback = async () => {
    if (fallbackCache !== undefined) return fallbackCache
    for (const candidate of FALLBACK_INDEX_CANDIDATES) {
      try {
        fallbackCache = await readFile(candidate)
        return fallbackCache
      } catch {
        // 尝试下一个候选
      }
    }
    fallbackCache = Buffer.from(
      '<!doctype html><html><head><title>AnotherViewer</title></head><body>SPA fallback</body></html>',
    )
    return fallbackCache
  }

  const server = createServer(async (req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname
    const hit = resolveRequest(pathname)
    if (hit.bad) {
      res.writeHead(400).end()
      return
    }
    if (hit.spa) {
      const body = await loadFallback()
      res.writeHead(200, { 'Content-Type': MIME['.html'] })
      res.end(body)
      return
    }
    try {
      const body = await readFile(hit.file)
      res.writeHead(200, { 'Content-Type': hit.mime })
      res.end(body)
    } catch {
      res.writeHead(404).end()
    }
  })

  await new Promise((res, rej) => {
    server.once('error', rej)
    server.listen(0, '127.0.0.1', res)
  })
  const { port } = server.address()
  return { server, origin: `http://127.0.0.1:${port}` }
}

/** GET 一个 URL，返回 { status, bytes, body }；网络错误时 status=0 */
async function get(url) {
  try {
    const resp = await fetch(url)
    const buf = Buffer.from(await resp.arrayBuffer())
    return { status: resp.status, bytes: buf.length, body: buf.toString('utf8') }
  } catch (err) {
    log(`  GET ${url} 网络错误：${err.message}`)
    return { status: 0, bytes: 0, body: '' }
  }
}

async function main() {
  const failures = []
  const check = (ok, message) => {
    if (!ok) failures.push(message)
    return ok
  }

  let server = null
  let base = process.env.BASE
  try {
    if (!base) {
      const started = await startServer()
      server = started.server
      base = started.origin
      log(`本地静态服务器已启动（root=public/）：${base}`)
    } else {
      log(`使用外部 BASE：${base}`)
    }
    if (!base.endsWith('/')) base += '/'

    // ── 1. /manifest.json 200 且 JSON 可解析 ────────────────────
    const mResp = await get(new URL('manifest.json', base).href)
    check(mResp.status === 200, `/manifest.json 期望 200，实际 ${mResp.status}`)
    let manifest = null
    if (mResp.status === 200) {
      try {
        manifest = JSON.parse(mResp.body)
      } catch (err) {
        failures.push(`/manifest.json JSON 解析失败：${err.message}`)
      }
    }

    if (manifest) {
      // ── 2. icons 每一项 src 逐个 GET 均 200 且字节数 > 0 ──────
      const icons = Array.isArray(manifest.icons) ? manifest.icons : []
      for (const icon of icons) {
        if (!icon || typeof icon.src !== 'string') {
          failures.push(`icons 中存在无 src 的条目：${JSON.stringify(icon)}`)
          continue
        }
        const url = new URL(icon.src, base).href
        const r = await get(url)
        const ok =
          check(r.status === 200, `icon ${icon.src} 期望 200，实际 ${r.status}`) &&
          check(r.bytes > 0, `icon ${icon.src} 响应字节数为 0`)
        log(`icon ${icon.src} → HTTP ${r.status}, ${r.bytes} bytes${ok ? '' : '  ✗'}`)
      }

      // ── 3. shortcuts 每一项 url 逐个 GET 200 ──────────────────
      const shortcuts = Array.isArray(manifest.shortcuts) ? manifest.shortcuts : []
      for (const sc of shortcuts) {
        if (!sc || typeof sc.url !== 'string') {
          failures.push(`shortcuts 中存在无 url 的条目：${JSON.stringify(sc)}`)
          continue
        }
        const url = new URL(sc.url, base).href
        const r = await get(url)
        const ok = check(r.status === 200, `shortcut ${sc.url} 期望 200，实际 ${r.status}`)
        log(`shortcut ${sc.url} → HTTP ${r.status}${ok ? '' : '  ✗'}`)
      }

      // ── 4. 结构校验 ───────────────────────────────────────────
      // 4.1 512 图标条目不再含 form_factor（icon 条目不支持该字段）
      const icon512 = icons.filter((i) => i && i.sizes === '512x512')
      check(icon512.length > 0, 'icons 中应仍存在 512x512 条目')
      for (const i of icon512) {
        check(
          !('form_factor' in i),
          `512x512 图标条目 ${i.src} 不应含 form_factor 字段（实际：${JSON.stringify(i)}）`,
        )
      }
      // 4.2 shortcuts 含 /eval 比例评估
      check(
        shortcuts.some((s) => s && s.url === '/eval' && s.name === '比例评估'),
        `shortcuts 应含 url==='/eval' 且 name==='比例评估'（实际：${JSON.stringify(shortcuts.map((s) => s && [s.url, s.name]))}）`,
      )
      // 4.3 id / display / orientation 不回归
      check(manifest.id === '/', `manifest.id 期望 '/'，实际 ${JSON.stringify(manifest.id)}`)
      check(
        manifest.display === 'standalone',
        `manifest.display 期望 'standalone'，实际 ${JSON.stringify(manifest.display)}`,
      )
      check(
        manifest.orientation === 'any',
        `manifest.orientation 期望 'any'，实际 ${JSON.stringify(manifest.orientation)}`,
      )
      log(
        `结构校验：icons=${icons.length}，shortcuts=${shortcuts.length}，id=${manifest.id}，display=${manifest.display}，orientation=${manifest.orientation}`,
      )
    }
  } finally {
    if (server) {
      await new Promise((res) => {
        server.closeAllConnections?.()
        server.close(res)
      })
      log('本地静态服务器已关闭')
    }
  }

  if (failures.length > 0) {
    log(`RESULT: FAIL — ${failures.length} 项断言失败`)
    for (const f of failures) log(`  ✗ ${f}`)
    process.exitCode = 1
    return
  }
  console.log('verify-manifest: PASS')
}

main().catch((err) => {
  log('RESULT: FAIL —', err.message)
  process.exitCode = 1
})
