<!--
  SetupView.vue — 服务器设置向导（PWA 多平台，plan-2026-09-06-pwa C7）。

  单卡片表单：输入候选服务器地址（实时归一化预览 + 行内错误）、「测试连接」、
  「使用当前地址」预填与「保存并重载」。

  探活契约：用裸 fetch 直连 `${候选base}/api/v1/auth/status`——绝不能走共享
  axios client（它的 baseURL 指向"当前"服务器而不是候选服务器）。判定：
  任何 HTTP 响应（200/401/5xx）都算可达，仅 fetch reject（网络层错误）算
  不可达；不用 /health 探活（EH 断连时它 503）。

  保存契约：setServerBase → SERVER_CONFIGURED_KEY='1' → 清 token/username →
  整页 reload（无热切换：axios/WS 单例与 KeepAlive 缓存会残留旧地址）。
-->
<template>
  <div class="setup-view">
    <div class="setup-view__column">
      <header class="setup-view__header">
        <h1 class="setup-view__title">服务器设置</h1>
        <p class="setup-view__subtitle">
          指定后端数据服务器的地址；保存后将清除本地登录态并重载页面。留空表示同源部署。
        </p>
      </header>

      <section class="setup-view__card">
        <AppTextField
          v-model="input"
          class="setup-view__field"
          label="服务器地址"
          placeholder="192.168.6.141:8081"
          inputmode="url"
          autocomplete="off"
          aria-label="服务器地址"
        />

        <!-- 归一化预览 / 行内错误（非法输入不抛异常，只展示红色提示）。 -->
        <p v-if="preview.error" class="setup-view__error" role="alert">{{ preview.error }}</p>
        <p v-else class="setup-view__preview" data-testid="server-preview">
          将连接到 <code>{{ preview.normalized === '' ? '（同源部署：当前页面地址）' : preview.normalized }}</code>
        </p>

        <!-- 混合内容警告：HTTPS 页面 → HTTP 服务器会被浏览器拦截。
             警告常显但不阻止保存（由用户自行决定）。 -->
        <p v-if="mixedContentWarning" class="setup-view__warning" role="alert">
          浏览器将拦截混合内容：当前页面通过 HTTPS 打开，而目标服务器是 HTTP，保存后请求会被浏览器阻止。
        </p>

        <!-- 测试连接结果态。 -->
        <p
          v-if="probe"
          class="setup-view__probe"
          :class="{ 'setup-view__probe--bad': !probe.reachable }"
          role="status"
          data-testid="probe-result"
        >
          {{ probeText }}
        </p>

        <div class="setup-view__actions">
          <button
            type="button"
            class="setup-btn setup-btn--ghost"
            :disabled="testing || preview.error !== ''"
            data-testid="probe-button"
            @click="testConnection"
          >
            {{ testing ? '测试中…' : '测试连接' }}
          </button>
          <button
            type="button"
            class="setup-btn setup-btn--ghost"
            data-testid="use-current-button"
            @click="useCurrentAddress"
          >
            使用当前地址
          </button>
          <button
            type="button"
            class="setup-btn setup-btn--primary"
            :disabled="preview.error !== ''"
            data-testid="save-button"
            @click="saveAndReload"
          >
            保存并重载
          </button>
        </div>
      </section>

      <p class="setup-view__footnote">
        探活使用 /api/v1/auth/status：任何 HTTP 响应（含 401/5xx）都视为服务器可达。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import AppTextField from '@/components/form/AppTextField.vue'
import {
  SERVER_CONFIGURED_KEY,
  ServerBaseError,
  normalizeServerBase,
  setServerBase,
} from '@/stores/server'

const input = ref('')

/** 归一化预览 + 行内错误（错误不向上抛，避免打到控制台）。 */
const preview = computed<{ normalized: string; error: string }>(() => {
  const raw = input.value.trim()
  if (raw === '') return { normalized: '', error: '' }
  try {
    return { normalized: normalizeServerBase(raw), error: '' }
  } catch (thrown) {
    const message =
      thrown instanceof ServerBaseError ? thrown.message : '无法解析的服务器地址'
    return { normalized: '', error: message }
  }
})

// 页面协议在整个页面生命周期内不变（切换服务器必然整页重载），setup 时捕获一次。
const pageProtocol = window.location.protocol

/** HTTPS 页面 → HTTP 服务器 = 混合内容，浏览器会拦截（警告常显，不阻止保存）。 */
const mixedContentWarning = computed(
  () => pageProtocol === 'https:' && preview.value.normalized.startsWith('http:'),
)

/* ------------------------------ 测试连接 ------------------------------- */

interface ProbeResult {
  reachable: boolean
  statusCode?: number
  authRequired?: boolean
}

const testing = ref(false)
const probe = ref<ProbeResult | null>(null)

const probeText = computed<string>(() => {
  const result = probe.value
  if (!result) return ''
  if (!result.reachable) return '无法连接：网络层错误（服务器无响应或地址不可达）'
  let text = `服务器可达（HTTP ${result.statusCode}）`
  if (result.authRequired !== undefined) {
    text += result.authRequired ? ' · 需要登录' : ' · 无需登录'
  }
  return text
})

async function testConnection(): Promise<void> {
  if (testing.value || preview.value.error) return
  testing.value = true
  probe.value = null
  // 裸 fetch 直连候选服务器——共享 axios client 的 baseURL 指向当前服务器，
  // 用来探活候选地址会打错目标。
  try {
    const response = await fetch(`${preview.value.normalized}/api/v1/auth/status`)
    let authRequired: boolean | undefined
    try {
      const data: unknown = await response.json()
      const candidate = (data as { authRequired?: unknown } | null)?.authRequired
      if (typeof candidate === 'boolean') authRequired = candidate
    } catch {
      // 非 JSON 响应体（如纯文本 5xx）——只展示可达性。
    }
    probe.value = { reachable: true, statusCode: response.status, authRequired }
  } catch {
    probe.value = { reachable: false }
  } finally {
    testing.value = false
  }
}

/* ------------------------------ 使用当前地址 ---------------------------- */

function useCurrentAddress(): void {
  // origin 不含路径；normalizeServerBase 再兜底去路径/补 scheme。
  try {
    input.value = normalizeServerBase(window.location.origin)
  } catch {
    // origin 形如 "null"（沙箱环境）时放弃预填，不打断用户。
  }
  probe.value = null
}

/* ------------------------------ 保存并重载 ------------------------------ */

function saveAndReload(): void {
  if (preview.value.error) return
  // 顺序契约（冻结）：保存地址 → 打 configured 标记 → 清本地登录态 → 整页重载。
  setServerBase(input.value)
  localStorage.setItem(SERVER_CONFIGURED_KEY, '1')
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  window.location.reload()
}
</script>

<style scoped>
.setup-view {
  min-height: 100%;
  background: var(--color-bg);
}

.setup-view__column {
  max-width: 640px;
  margin: 0 auto;
  padding: calc(16px + var(--safe-area-top)) var(--keyline-margin)
    calc(56px + var(--safe-area-bottom));
}

/* --------------------------------- header -------------------------------- */

.setup-view__header {
  padding: 12px 4px 16px;
}

.setup-view__title {
  margin: 0;
  font-size: clamp(17px, 20px, 24px);
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--text-color-primary);
}

.setup-view__subtitle {
  margin: 6px 0 0;
  font-size: clamp(12px, 13px, 15px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}

/* ---------------------------------- card --------------------------------- */

.setup-view__card {
  padding: 20px var(--keyline-margin);
  border-radius: var(--card-radius);
  background: var(--color-background-floating);
  box-shadow:
    0 var(--card-elevation) 4px var(--shadow-color),
    0 0 1px var(--shadow-color);
}

.setup-view__field {
  margin-bottom: 10px;
}

.setup-view__preview {
  margin: 0;
  font-size: clamp(12px, 13px, 15px);
  color: var(--text-color-secondary);
  overflow-wrap: anywhere;
}

.setup-view__preview code {
  padding: 1px 6px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--color-primary) 10%, transparent);
  color: var(--color-primary-text);
  font-variant-numeric: tabular-nums;
}

.setup-view__error {
  margin: 0;
  font-size: clamp(12px, 13px, 15px);
  color: var(--color-red-500);
}

.setup-view__warning {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: var(--card-radius);
  background: color-mix(in srgb, var(--color-red-500) 12%, transparent);
  color: var(--color-red-500);
  font-size: clamp(12px, 13px, 15px);
  line-height: 1.45;
}

.setup-view__probe {
  margin: 10px 0 0;
  font-size: clamp(12px, 13px, 15px);
  color: var(--color-primary-text);
}

.setup-view__probe--bad {
  color: var(--color-red-500);
}

/* --------------------------------- actions ------------------------------- */

.setup-view__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}

.setup-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 18px;
  border-radius: var(--card-radius);
  font-size: clamp(13px, 14px, 16px);
  font-weight: 700;
  cursor: pointer;
  transition:
    background-color 150ms var(--ease-decelerate-quart),
    border-color 150ms var(--ease-decelerate-quart),
    transform 120ms var(--ease-decelerate-quart);
}

.setup-btn:active:not(:disabled) {
  transform: scale(0.985);
}

.setup-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.setup-btn--ghost {
  border: 1px solid var(--color-divider);
  background: transparent;
  color: var(--color-primary-text);
}

.setup-btn--ghost:hover:not(:disabled) {
  border-color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 8%, transparent);
}

.setup-btn--primary {
  border: none;
  background: var(--color-primary);
  color: var(--color-white);
  box-shadow: 0 2px 6px var(--shadow-color);
}

.setup-btn--primary:hover:not(:disabled) {
  background: var(--color-primary-dark);
}

/* -------------------------------- footnote ------------------------------- */

.setup-view__footnote {
  margin: 14px 4px 0;
  font-size: clamp(11px, 12px, 14px);
  line-height: 1.5;
  color: var(--text-color-secondary);
}
</style>
