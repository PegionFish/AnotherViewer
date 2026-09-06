#!/usr/bin/env node
/**
 * verify-remote-sw.mjs — 远程模式（跨域）API 走 SW 缓存策略的端到端验证
 *
 * 验证目标（对应 sw.js fetch 规则 3 去掉 origin 门）：
 *   跨域 /api/ 请求（远程模式后端返回 cors 响应）与同源一致走
 *   NetworkFirst（networkFirstApi），离线时回退 SW CacheStorage；
 *   而不在 /api/ 前缀下的跨域请求不被缓存。
 *
 * 流程（全自包含，不依赖真实后端 / 不跑 vite build）：
 *   1. node:http 起 stub API 服务器（127.0.0.1:18081 起，端口被占则 +1 顺延）：
 *      - GET /api/v1/auth/status → 200 JSON + ACAO:* + Access-Control-Expose-Headers
 *      - GET /health（非 /api/ 对照 URL）→ 200 JSON + 同样 CORS 头
 *      - 响应带 Cache-Control: no-store，排除浏览器 HTTP 缓存混淆
 *        （确保停服后命中的是 SW CacheStorage 而非 HTTP cache）。
 *   2. node:http 起壳服务器（127.0.0.1:18082 起）：root=public/ 直出真实
 *      sw.js / manifest / 图标；'/' 与 '/index.html' 换成内嵌生成的 harness
 *      页面（注册 '/sw.js' → 等 controller → 跨域 fetch stub → 把结果与
 *      caches 状态写 DOM/console，并暴露 window.__harness 供脚本驱动）。
 *      localhost/127.0.0.1 是安全上下文，SW 可注册，无需 HTTPS。
 *   3. playwright chromium 断言：
 *      ① SW 激活且 navigator.serviceWorker.controller 非空；
 *      ② 跨域 fetch 后 caches.keys() 出现 `<CACHE_NAME>-api` 且
 *         caches.match('<stub 完整 URL>') 命中；
 *      ③ 停掉 stub 服务器后再 fetch 同一 URL，仍返回 200 + 同 body，
 *         且带 x-sw-cached-at 戳（证明来自 SW 缓存副本而非网络）；
 *      ④ 反向对照：非 /api/ 前缀的跨域 URL 不被任何 SW 缓存收录。
 *   4. 全部通过输出 `verify-remote-sw: PASS`；任何断言失败打印现场
 *      （页面 console 日志、caches 内容）并非零退出。
 *
 * 可重复运行：端口占用自动顺延；playwright 每次 launch 都是全新 profile
 * （无历史 SW/缓存）；harness 注册前还会主动清理旧注册与 caches 兜底；
 * try/finally 保证浏览器与两个服务器最终关闭。
 *
 * 用法：cd web-frontend && node e2e/verify-remote-sw.mjs
 */
import { createRequire } from 'node:module'
import http from 'node:http'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

// @playwright/test 是 CJS 包；.mjs 里用 createRequire 引入（web-frontend/node_modules）
const require = createRequire(import.meta.url)
const { chromium } = require('@playwright/test')

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PUBLIC_ROOT = path.resolve(HERE, '..', 'public')
const HOST = '127.0.0.1'
const STUB_PORT0 = 18081
const SHELL_PORT0 = 18082

// 从 public/sw.js 解析 CACHE_NAME，避免文档/断言与实现漂移
const SW_SOURCE = readFileSync(path.join(PUBLIC_ROOT, 'sw.js'), 'utf8')
const CACHE_NAME = SW_SOURCE.match(/const CACHE_NAME = '([^']+)'/)?.[1]
if (!CACHE_NAME) {
  console.error('[verify-remote-sw] FAIL — 无法从 public/sw.js 解析 CACHE_NAME')
  process.exit(1)
}
const API_CACHE = `${CACHE_NAME}-api`

const API_PATH = '/api/v1/auth/status'
const NEG_PATH = '/health'
const STUB_BODY = JSON.stringify({
  service: 'stub-api',
  path: API_PATH,
  authenticated: true,
  ts: 1725600000000,
})
const NEG_BODY = JSON.stringify({ service: 'stub-api', negative: true })

function log(...args) {
  console.log('[verify-remote-sw]', ...args)
}

/* --------------------------------------------------------------------------
 * 服务器
 * ------------------------------------------------------------------------ */

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  // 让页面能读到 SW 打上去的时间戳，作为"命中 SW 缓存"的证据之一
  'Access-Control-Expose-Headers': 'x-sw-cached-at',
  // 关键：禁用浏览器 HTTP 缓存，停服后能命中只能是 SW CacheStorage
  'Cache-Control': 'no-store',
}

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...CORS_HEADERS })
  res.end(body)
}

function startStubServer() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`)
    if (req.method === 'OPTIONS') {
      res.writeHead(204, CORS_HEADERS)
      res.end()
      return
    }
    if (url.pathname === API_PATH) return sendJson(res, 200, STUB_BODY)
    if (url.pathname === NEG_PATH) return sendJson(res, 200, NEG_BODY)
    sendJson(res, 404, JSON.stringify({ error: 'not found', path: url.pathname }))
  })
  return server
}

const MIME = {
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css',
  '.md': 'text/markdown; charset=utf-8',
}

function startShellServer(harnessHtml) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`)
    const pathname = decodeURIComponent(url.pathname)

    // 壳入口换成 harness 页面（其余文件直出 public/ 真实内容）
    if (pathname === '/' || pathname === '/index.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      res.end(harnessHtml)
      return
    }

    // 防目录穿越
    const safe = path.normalize(pathname).replace(/^(\.\.[/\\])+/, '')
    const file = path.join(PUBLIC_ROOT, safe)
    if (!file.startsWith(PUBLIC_ROOT)) {
      res.writeHead(403)
      res.end()
      return
    }
    try {
      const data = readFileSync(file)
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' })
      res.end(data)
    } catch {
      res.writeHead(404)
      res.end('not found')
    }
  })
  return server
}

/** harness 页面：注册 SW → 等 controller → 跨域 fetch stub → 写 DOM/console。 */
function buildHarnessHtml(stubOrigin) {
  const apiUrl = `${stubOrigin}${API_PATH}`
  const negUrl = `${stubOrigin}${NEG_PATH}`
  return `<!doctype html>
<html>
<head><meta charset="utf-8"><title>remote-sw harness</title></head>
<body>
<pre id="status">harness: booting</pre>
<script>
  const API_URL = ${JSON.stringify(apiUrl)}
  const NEG_URL = ${JSON.stringify(negUrl)}

  function report(label, data) {
    const line = label + ': ' + JSON.stringify(data)
    console.log('[harness]', line)
    const pre = document.getElementById('status')
    pre.textContent += '\\n' + line
    return data
  }

  async function dumpCaches() {
    const keys = await caches.keys()
    const dump = {}
    for (const k of keys) {
      const cache = await caches.open(k)
      dump[k] = (await cache.keys()).map((r) => r.url)
    }
    return dump
  }

  window.__harness = {
    // 注册 SW 并等待其控制页面（先清理旧注册/旧缓存，保证幂等）
    async register() {
      for (const reg of await navigator.serviceWorker.getRegistrations()) await reg.unregister()
      for (const key of await caches.keys()) await caches.delete(key)

      const reg = await navigator.serviceWorker.register('/sw.js')
      const worker = reg.installing || reg.waiting || reg.active
      if (worker && worker.state !== 'activated') {
        await new Promise((resolve) => {
          worker.addEventListener('statechange', function onUpdate() {
            if (worker.state === 'activated') { worker.removeEventListener('statechange', onUpdate); resolve() }
          })
        })
      }
      if (!navigator.serviceWorker.controller) {
        await new Promise((resolve) =>
          navigator.serviceWorker.addEventListener('controllerchange', resolve, { once: true })
        )
      }
      return report('register', {
        controller: !!navigator.serviceWorker.controller,
        state: reg.active && reg.active.state,
      })
    },

    // 经 SW 的跨域 cors fetch（默认 mode: 'cors'）
    async fetchApi() {
      const resp = await fetch(API_URL)
      const body = await resp.text()
      return report('fetchApi', {
        status: resp.status,
        body,
        swStamp: resp.headers.get('x-sw-cached-at'),
      })
    },

    // 对照：非 /api/ 前缀的跨域 fetch
    async fetchNegative() {
      const resp = await fetch(NEG_URL)
      const body = await resp.text()
      return report('fetchNegative', { status: resp.status, body })
    },

    async cacheState() {
      const apiMatch = await caches.match(API_URL)
      const negMatch = await caches.match(NEG_URL)
      let apiMatchBody = null
      if (apiMatch) apiMatchBody = await apiMatch.text()
      return report('cacheState', {
        keys: await caches.keys(),
        apiMatchHit: !!apiMatch,
        apiMatchBody,
        negMatchHit: !!negMatch,
        dump: await dumpCaches(),
      })
    },
  }

  report('ready', { apiUrl: API_URL, negUrl: NEG_URL })
</script>
</body>
</html>`
}

/* --------------------------------------------------------------------------
 * 小工具
 * ------------------------------------------------------------------------ */

function listenOn(server, port, host) {
  return new Promise((resolve, reject) => {
    const onError = (err) => { cleanup(); reject(err) }
    const onListening = () => { cleanup(); resolve() }
    function cleanup() {
      server.removeListener('error', onError)
      server.removeListener('listening', onListening)
    }
    server.once('error', onError)
    server.once('listening', onListening)
    server.listen(port, host)
  })
}

/** 端口被占则 +1 顺延（最多尝试 10 个），返回实际端口。 */
async function listenWithFallback(server, startPort, host, label) {
  let port = startPort
  for (let i = 0; i < 10; i++, port++) {
    try {
      await listenOn(server, port, host)
      return port
    } catch (err) {
      if (err.code !== 'EADDRINUSE') throw err
      log(`${label} 端口 ${port} 被占，尝试 ${port + 1}`)
    }
  }
  throw new Error(`${label} 在 ${startPort}..${port - 1} 均无法监听`)
}

function closeServer(server) {
  return new Promise((resolve) => {
    if (!server) return resolve()
    if (typeof server.closeAllConnections === 'function') server.closeAllConnections()
    server.close(() => resolve())
  })
}

function assert(cond, message, details) {
  if (cond) {
    log(`PASS — ${message}`)
    return
  }
  throw Object.assign(new Error(`断言失败：${message}`), { details })
}

/* --------------------------------------------------------------------------
 * 主流程
 * ------------------------------------------------------------------------ */

async function main() {
  let stubServer = null
  let shellServer = null
  let browser = null
  const pageLogs = []

  try {
    // ── 1. 起两个服务器 ────────────────────────────────────────
    stubServer = startStubServer()
    const stubPort = await listenWithFallback(stubServer, STUB_PORT0, HOST, 'stub-api')
    const stubOrigin = `http://${HOST}:${stubPort}`
    log(`stub API 服务器就绪：${stubOrigin}${API_PATH}（对照 ${stubOrigin}${NEG_PATH}）`)

    shellServer = startShellServer(buildHarnessHtml(stubOrigin))
    const shellPort = await listenWithFallback(shellServer, SHELL_PORT0, HOST, 'shell')
    const shellOrigin = `http://${HOST}:${shellPort}`
    log(`壳服务器就绪：${shellOrigin}/（root=public/，'/' 与 '/index.html' 为 harness）`)

    const API_URL = `${stubOrigin}${API_PATH}`
    const NEG_URL = `${stubOrigin}${NEG_PATH}`

    // ── 2. 起 chromium，打开 harness 页 ───────────────────────
    browser = await chromium.launch()
    const context = await browser.newContext()
    const page = await context.newPage()
    page.setDefaultTimeout(15000)
    page.on('console', (msg) => pageLogs.push(`[page:${msg.type()}] ${msg.text()}`))
    page.on('pageerror', (err) => pageLogs.push(`[pageerror] ${err.message}`))

    await page.goto(`${shellOrigin}/`, { waitUntil: 'load' })

    // ── 断言 ①：SW 激活且控制页面 ─────────────────────────────
    const regState = await page.evaluate(() => window.__harness.register())
    assert(
      regState.controller === true && regState.state === 'activated',
      `① SW 已激活并控制页面（controller=${regState.controller}, state=${regState.state}）`
    )

    // ── 断言 ②：跨域 cors fetch 后进入 api 缓存 ───────────────
    const online = await page.evaluate(() => window.__harness.fetchApi())
    assert(online.status === 200, `② 在线跨域 fetch 返回 200（实际 ${online.status}）`)
    assert(online.body === STUB_BODY, '② 在线跨域 fetch body 与 stub 一致')

    const cacheState = await page.evaluate(() => window.__harness.cacheState())
    assert(
      cacheState.keys.includes(API_CACHE),
      `② caches.keys() 出现 api 缓存 ${API_CACHE}（实际 [${cacheState.keys.join(', ')}]）`
    )
    assert(
      cacheState.apiMatchHit === true && cacheState.apiMatchBody === STUB_BODY,
      `② caches.match('${API_URL}') 命中且 body 一致`
    )
    assert(cacheState.negMatchHit === false, `④ 对照 URL ${NEG_URL} 未被缓存收录`)

    // ── 断言 ③：停掉 stub 后仍返回 200 + 同 body ──────────────
    await closeServer(stubServer)
    stubServer = null
    log('stub API 服务器已停止')

    const offline = await page.evaluate(() => window.__harness.fetchApi())
    assert(offline.status === 200, `③ stub 停止后 fetch 返回 200（实际 ${offline.status}）`)
    assert(offline.body === STUB_BODY, '③ stub 停止后 body 与 stub 一致（命中 SW 缓存）')
    assert(
      !!offline.swStamp,
      `③ 响应带 x-sw-cached-at 戳（=${offline.swStamp}），确认来自 SW 缓存副本`
    )

    // ── 断言 ④（复核）：对照 URL 仍不在任何 SW 缓存 ────────────
    const cacheState2 = await page.evaluate(() => window.__harness.cacheState())
    const allUrls = Object.values(cacheState2.dump).flat()
    assert(
      !allUrls.includes(NEG_URL),
      `④ 复核：${NEG_URL} 不在任何 SW 缓存中`
    )

    log('caches 终态：', JSON.stringify(cacheState2.dump, null, 2))
    log('verify-remote-sw: PASS')
  } catch (err) {
    log('FAIL —', err.message)
    console.error('\n===== 现场：页面 console 日志 =====')
    for (const line of pageLogs) console.error(line)
    console.error('===== 现场：caches 内容（最后一次已知状态） =====')
    try {
      const pages = browser ? browser.contexts().flatMap((c) => c.pages()) : []
      if (pages.length && pages[0].url() !== 'about:blank') {
        const dump = await pages[0].evaluate(() => window.__harness.cacheState()).catch((e) => ({ error: e.message }))
        console.error(JSON.stringify(dump, null, 2))
      } else {
        console.error('(页面不可用)')
      }
    } catch (e) {
      console.error('(无法读取 caches：', e.message, ')')
    }
    process.exitCode = 1
  } finally {
    await closeServer(stubServer)
    await closeServer(shellServer)
    if (browser) await browser.close().catch(() => {})
    log('清理完成（stub/壳服务器与浏览器均已关闭）')
  }
}

main()
