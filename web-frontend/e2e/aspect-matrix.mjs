#!/usr/bin/env node
/**
 * aspect-matrix.mjs — C11: canonical 比例矩阵「真窗口 vs 评估台」对拍。
 *
 * 对 4 个 canonical 比例（16:9 / 16:10 / 4:3 / 3:2，见
 * src/composables/useViewportFrame.ts CANONICAL）× 2 方向（land/port）×
 * 4 路由（/ , /downloads , /settings/general , /search），各截两张图并逐像素
 * 对拍：
 *
 *   A) real-*  ：真窗口 newContext({viewport: canonical}) 直开路由；
 *   B) eval-*  ：1920 宽评估台视口开 /eval?ratio=…&orient=…&scale=100，
 *                等 iframe 内路由挂载后取 iframe boundingBox()（scale=100
 *                档下恰为 canonical px，transform 不改变 iframe 的布局
 *                viewport），以 clip 截取。
 *
 * 两侧同渲染引擎同 DPR 同内容（/api/v1/** 全量 stub 成空数据信封，不抓取
 * 任何画廊内容），理论上 diffRatio 应≈0；阈值 ≤0.02（2%）判过，超阈值写
 * OUT_DIR/diff-*.png 并标红，退出码非零。
 *
 * 产物：OUT_DIR/real-*.png、eval-*.png（失败另有 diff-*.png）+ report.json
 * + 控制台表格与总结行。OUT_DIR 缺省 e2e/aspect-actual（未跟踪目录）。
 *
 * 用法：node e2e/aspect-matrix.mjs [OUT_DIR]
 *
 * 自包含：脚本内部拉起/杀掉本地 vite dev server（--host 127.0.0.1 --port
 * 5199 --strictPort；显式 host 使 127.0.0.1 可达——vite 缺省只绑 ::1）。
 *
 * 注：评估台视口用 1920×1400（卡面曾写 1200 高）：.eval-stage 可用高度 =
 * innerHeight - 56（EVAL_TOOLBAR_H），1200 高下竖屏 16:9/16:10（1280 高）
 * 与 3:2（1200 高）取景框会被 overflow:hidden 裁掉底部，clip 截不满
 * canonical 画布，对拍必挂；1400 高（可用 1344 ≥ 1280）让全部 8 个组合的
 * 取景框完整落在视口内，其余不变。
 */
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import pixelmatch from 'pixelmatch'

const require = createRequire(import.meta.url)
const { chromium } = require('@playwright/test')

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const WEB_ROOT = path.resolve(__dirname, '..')

const PORT = Number(process.env.ASPECT_PORT || 5199)
const BASE = `http://127.0.0.1:${PORT}`
const OUT_DIR = path.resolve(process.argv[2] || path.join(__dirname, 'aspect-actual'))

const SETTLE_MS = 700 // 'load' + shell selector 后的落定等待（同 visual.spec 惯例）
const DIFF_MAX = 0.02 // 对拍通过阈值：diff 像素占比 ≤ 2%
const PIXELMATCH_THRESHOLD = 0.1 // 同 compare.mjs 缺省色容差

// canonical 画布（横屏 w×h；竖屏互换）——与 CANONICAL 一一对应
const ASPECTS = [
  { ratio: '16:9', w: 1280, h: 720 },
  { ratio: '16:10', w: 1280, h: 800 },
  { ratio: '4:3', w: 1024, h: 768 },
  { ratio: '3:2', w: 1200, h: 800 },
]
const ORIENTS = ['land', 'port']
const ROUTES = [
  { path: '/', slug: 'home' },
  { path: '/downloads', slug: 'downloads' },
  { path: '/settings/general', slug: 'settings-general' },
  { path: '/search', slug: 'search' },
]

// 评估台宿主视口：只需容得下最大取景框（1280 宽 / 1280 高）+ 56 工具条
const EVAL_VIEWPORT = { width: 1920, height: 1400 }

// ────────────────────────────── API stub ──────────────────────────────

/**
 * 与 src/api/preferences.ts DEFAULT_PREFERENCES 同形的缺省偏好（脚本侧
 * 常量，供 /preferences 空态渲染出真实默认控件而非报错）。后端缺省值更新
 * 时此处需手动同步——只影响空态长相，两侧一致即不影响对拍有效性。
 */
const STUB_PREFERENCES = {
  general: {
    theme: 'light',
    themeAutoSwitch: false,
    launchPage: 'homepage',
    listMode: 'grid',
    showReadProgress: true,
    detailSize: 'long',
    thumbSize: 'middle',
    historyInfoSize: 100,
    showJpnTitle: false,
    showGalleryPages: false,
    showTagTranslations: true,
    showGalleryComment: true,
    showGalleryRating: true,
    showEhEvents: true,
    showEhLimits: true,
    showUploader: false,
    showPostedTime: false,
    defaultFavoriteSlot: 0,
    favoriteSlotNames: '',
    recentSearchMax: 10,
  },
  reader: {
    readingDirection: 'rtl',
    pageMode: 'auto',
    firstPageCover: true,
    pageScaling: 'fit',
    startPosition: 'top_right',
    autoPlayIntervalSec: 2,
    showProgress: true,
    showPageInterval: true,
    fullscreen: true,
    brightness: 0,
    backgroundColor: 'black',
    tapZoneScheme: 'threeZone',
    keyboardPaging: true,
    zoomStep: 1.5,
    maxZoom: 5,
    dualPageGap: 8,
    splitWidePages: false,
    preloadCount: 2,
    pageTransition: 'slide',
  },
  privacy: { enableAnalytics: true },
}

/** 通用成功空数据信封：列表（success/data/total）+ 下载列表（downloads/labels）并集。 */
const STUB_EMPTY_ENVELOPE = { success: true, data: [], total: 0, downloads: [], labels: [] }

function json(route, body) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json; charset=utf-8',
    body: JSON.stringify(body),
  })
}

/** context.route 拦截全部 /api/v1 请求：让 4 个目标路由渲染空态、守卫放行。 */
async function stubApiV1(route) {
  const { pathname } = new URL(route.request().url())
  if (pathname.endsWith('/auth/status')) {
    return json(route, { authenticated: false, authRequired: false })
  }
  if (pathname.endsWith('/preferences')) {
    return json(route, STUB_PREFERENCES)
  }
  return json(route, STUB_EMPTY_ENVELOPE)
}

// ───────────────────────── 页面稳定性（脚本侧） ─────────────────────────
// 同 e2e/visual.spec.ts：固定主题 + 冻结动画/过渡/光标，保证逐帧可复现。
const STABILIZE_CSS =
  '*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important;scroll-behavior:auto!important}'

function initScript({ css }) {
  try {
    window.localStorage.setItem('anotherviewer-theme', 'light')
  } catch {
    /* storage 不可用——非致命 */
  }
  const apply = () => {
    const s = document.createElement('style')
    s.setAttribute('data-e2e-stabilize', '')
    s.textContent = css
    document.head.appendChild(s)
  }
  if (document.head) apply()
  else document.addEventListener('DOMContentLoaded', apply)
}

async function newStubbedContext(browser, options) {
  const ctx = await browser.newContext({
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
    ...options,
  })
  // context 级路由/init 脚本同样作用于 eval 页里的 iframe。
  await ctx.route('**/api/v1/**', stubApiV1)
  await ctx.addInitScript(initScript, { css: STABILIZE_CSS })
  return ctx
}

// ──────────────────────────── dev server ────────────────────────────

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function startDevServer() {
  const child = spawn(
    'npm',
    ['run', 'dev', '--', '--host', '127.0.0.1', '--port', String(PORT), '--strictPort'],
    { cwd: WEB_ROOT, stdio: ['ignore', 'pipe', 'pipe'], detached: true, env: process.env },
  )
  let output = ''
  child.stdout.on('data', (d) => (output += d))
  child.stderr.on('data', (d) => (output += d))

  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`dev server 提前退出（code=${child.exitCode}）:\n${output}`)
    }
    try {
      const res = await fetch(`${BASE}/`)
      if (res.ok) return child
    } catch {
      /* 还没就绪，继续轮询 */
    }
    await sleep(250)
  }
  throw new Error(`dev server 60s 未就绪:\n${output}`)
}

/** 杀掉整个进程树（npm → vite）：先 SIGTERM 进程组，2s 后仍活着则 SIGKILL。 */
async function stopDevServer(child) {
  if (!child || child.exitCode !== null) return
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    /* 进程组可能已退出 */
  }
  for (let i = 0; i < 20 && child.exitCode === null; i++) await sleep(100)
  if (child.exitCode === null) {
    try {
      process.kill(-child.pid, 'SIGKILL')
    } catch {
      /* ignore */
    }
  }
}

// ───────────────────────────── 截图 A/B ─────────────────────────────

function canonicalOf(aspect, orient) {
  return orient === 'land' ? { width: aspect.w, height: aspect.h } : { width: aspect.h, height: aspect.w }
}

function shotName(kind, aspect, orient, route) {
  const ratioId = aspect.ratio.replace(':', 'x')
  return `${kind}-${ratioId}-${orient}-${route.slug}.png`
}

/** A) 真窗口：canonical viewport 直开路由，整视口截图。 */
async function captureReal(browser, aspect, orient, route) {
  const { width, height } = canonicalOf(aspect, orient)
  const ctx = await newStubbedContext(browser, { viewport: { width, height } })
  try {
    const page = await ctx.newPage()
    await page.goto(`${BASE}${route.path}`, { waitUntil: 'load', timeout: 30_000 })
    await page.waitForSelector('.app-layout', { timeout: 30_000 })
    const pathname = new URL(page.url()).pathname
    if (pathname !== route.path) {
      throw new Error(`路由被重定向到 ${pathname}（守卫未放行？）`)
    }
    await page.waitForTimeout(SETTLE_MS)
    const file = path.join(OUT_DIR, shotName('real', aspect, orient, route))
    await page.screenshot({ path: file, clip: { x: 0, y: 0, width, height }, animations: 'disabled' })
    return file
  } finally {
    await ctx.close()
  }
}

/**
 * B) 评估台：/eval?ratio&orient&scale=100，iframe 导航到目标路由后按
 * boundingBox clip 截图。scale=100（非 fit）是有意为之：fit 档在 1920 宽
 * 宿主下 scale>1 会引入重采样噪声；100 档 transform=1，iframe 布局
 * viewport 恰为 canonical px。
 */
async function captureEval(browser, aspect, orient, route) {
  const { width, height } = canonicalOf(aspect, orient)
  const ctx = await newStubbedContext(browser, { viewport: EVAL_VIEWPORT })
  try {
    const page = await ctx.newPage()
    const query = `ratio=${encodeURIComponent(aspect.ratio)}&orient=${orient}&scale=100`
    await page.goto(`${BASE}/eval?${query}`, { waitUntil: 'load', timeout: 30_000 })
    // 工具条等宿主 chrome 不进 clip，无需处理；仅隐藏悬浮角标（宿主 UI，
    // 悬在取景框左上角、非应用内容），避免它污染对拍区域。
    await page.addStyleTag({ content: '.eval-stage__badge{display:none!important}' })

    const iframe = page.locator('iframe.eval-frame__surface')
    await iframe.waitFor({ state: 'attached', timeout: 30_000 })
    const frame = await (await iframe.elementHandle()).contentFrame()
    if (!frame) throw new Error('eval iframe 无 content frame')

    // 等 iframe 里的初始 '/' 完成守卫 + 挂载，再导航到目标路由（同源）。
    await frame.waitForSelector('.app-layout', { timeout: 30_000 })
    await frame.goto(`${BASE}${route.path}`, { waitUntil: 'load', timeout: 30_000 })
    await frame.waitForSelector('.app-layout', { timeout: 30_000 })
    const framePath = new URL(frame.url()).pathname
    if (framePath !== route.path) {
      throw new Error(`iframe 内路由被重定向到 ${framePath}（守卫未放行？）`)
    }
    await page.waitForTimeout(SETTLE_MS)

    const box = await iframe.boundingBox()
    if (!box) throw new Error('取不到 iframe boundingBox')
    // scale=100 档下 boundingBox 应恰为 canonical px——不符说明取景框状态
    // 与 query 不一致（如 query 未生效），直接报错而非产出无效对拍。
    if (Math.round(box.width) !== width || Math.round(box.height) !== height) {
      throw new Error(`iframe box ${Math.round(box.width)}x${Math.round(box.height)} ≠ canonical ${width}x${height}`)
    }
    const file = path.join(OUT_DIR, shotName('eval', aspect, orient, route))
    await page.screenshot({
      path: file,
      clip: { x: Math.round(box.x), y: Math.round(box.y), width, height },
      animations: 'disabled',
    })
    return file
  } finally {
    await ctx.close()
  }
}

// ────────────────────────────── 对拍 ──────────────────────────────

function comparePair(realFile, evalFile, diffFile) {
  const a = PNG.sync.read(fs.readFileSync(realFile))
  const b = PNG.sync.read(fs.readFileSync(evalFile))
  if (a.width !== b.width || a.height !== b.height) {
    return { error: `尺寸不一致 real ${a.width}x${a.height} vs eval ${b.width}x${b.height}` }
  }
  const diff = new PNG({ width: a.width, height: a.height })
  const diffPixels = pixelmatch(a.data, b.data, diff.data, a.width, a.height, {
    threshold: PIXELMATCH_THRESHOLD,
  })
  const diffRatio = diffPixels / (a.width * a.height)
  const pass = diffRatio <= DIFF_MAX
  if (!pass) fs.writeFileSync(diffFile, PNG.sync.write(diff))
  return { diffRatio, pass }
}

// ────────────────────────────── 主流程 ──────────────────────────────

const RED = '\x1b[31m'
const GREEN = '\x1b[32m'
const RESET = '\x1b[0m'

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true })
  const devServer = await startDevServer()
  let browser
  const rows = []
  try {
    browser = await chromium.launch()
    const total = ASPECTS.length * ORIENTS.length * ROUTES.length
    for (const aspect of ASPECTS) {
      for (const orient of ORIENTS) {
        for (const route of ROUTES) {
          const realFile = await captureReal(browser, aspect, orient, route)
          const evalFile = await captureEval(browser, aspect, orient, route)
          const diffFile = path.join(OUT_DIR, shotName('diff', aspect, orient, route))
          const result = comparePair(realFile, evalFile, diffFile)
          rows.push({
            ratio: aspect.ratio,
            orient,
            route: route.path,
            slug: route.slug,
            width: canonicalOf(aspect, orient).width,
            height: canonicalOf(aspect, orient).height,
            diffRatio: result.error ? null : Number(result.diffRatio.toFixed(6)),
            pass: result.pass === true,
            error: result.error ?? null,
          })
        }
      }
    }
  } finally {
    if (browser) await browser.close().catch(() => {})
    await stopDevServer(devServer)
  }

  // 控制台表格
  console.log(
    `aspect-matrix 对拍（real vs eval，阈值 diffRatio ≤ ${DIFF_MAX}，pixelmatch=${PIXELMATCH_THRESHOLD}）`,
  )
  console.log('-'.repeat(76))
  console.log(
    'combo'.padEnd(18) + 'route'.padEnd(20) + 'px'.padEnd(12) + 'diffRatio'.padEnd(12) + 'result',
  )
  console.log('-'.repeat(76))
  for (const r of rows) {
    const combo = `${r.ratio} ${r.orient}`
    const px = `${r.width}x${r.height}`
    const cell = r.error
      ? `${RED}ERROR${RESET}`
      : r.pass
        ? `${GREEN}PASS${RESET}`
        : `${RED}FAIL${RESET}`
    const value = r.error ?? r.diffRatio.toFixed(5)
    console.log(`${combo.padEnd(18)}${r.route.padEnd(20)}${px.padEnd(12)}${value.padEnd(12)}${cell}`)
  }
  console.log('-'.repeat(76))

  fs.writeFileSync(path.join(OUT_DIR, 'report.json'), JSON.stringify(rows, null, 2) + '\n')

  const passed = rows.filter((r) => r.pass).length
  const failedRows = rows.filter((r) => !r.pass)
  if (failedRows.length === 0) {
    console.log(`aspect-matrix: ${passed}/${rows.length} PASS`)
    return
  }
  console.error(`aspect-matrix: ${passed}/${rows.length} PASS，失败项：`)
  for (const r of failedRows) {
    console.error(
      `  ✗ ${r.ratio} ${r.orient} ${r.route}${r.error ? ` — ${r.error}` : ` — diffRatio=${r.diffRatio.toFixed(5)}（diff 图已写入 ${shotName('diff', { ratio: r.ratio }, r.orient, { slug: r.slug })}）`}`,
    )
  }
  process.exitCode = 1
}

main().catch((err) => {
  console.error('aspect-matrix 运行失败:', err)
  process.exitCode = 2
})
