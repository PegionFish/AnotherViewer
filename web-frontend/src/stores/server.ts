/**
 * Configurable data-server base for PWA/remote mode (plan-2026-09-06-pwa §3.1).
 *
 * Module-level singleton, NOT a Pinia store: `api/client.ts` reads it at
 * module load time to derive its baseURL, before any Pinia instance exists.
 * `''` means same-origin deployment — every derived URL is byte-identical to
 * the pre-PWA hardcoded form (hard zero-regression anchor).
 *
 * Switching servers = setServerBase() + clear auth + full page reload;
 * there is deliberately no hot-swap (axios/WS singletons and KeepAlive
 * caches would leak stale URLs).
 */
import { ref } from 'vue'

/** localStorage key holding the normalized absolute origin ('' = same-origin). */
export const SERVER_BASE_KEY = 'server-base'

/** localStorage key stamped '1' once the user explicitly configured a server. */
export const SERVER_CONFIGURED_KEY = 'server-configured'

/** Thrown by normalizeServerBase for unparseable input or non-http(s) schemes. */
export class ServerBaseError extends Error {
  constructor(message = 'invalid server base') {
    super(message)
    this.name = 'ServerBaseError'
  }
}

/**
 * Normalize user input into an absolute origin:
 * '' stays ''; a missing scheme gets 'http://'; only the origin is kept
 * (path/trailing slash dropped). Anything unparseable as http(s) throws.
 */
export function normalizeServerBase(raw: string): string {
  const trimmed = raw.trim()
  if (trimmed === '') return ''
  const withScheme = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)
    ? trimmed
    : `http://${trimmed}`
  let parsed: URL
  try {
    parsed = new URL(withScheme)
  } catch {
    throw new ServerBaseError(`无法解析的服务器地址：${raw}`)
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new ServerBaseError(`仅支持 http/https 地址：${raw}`)
  }
  return parsed.origin
}

/** Current server base ('' = same-origin); tolerant to broken localStorage. */
export function getServerBase(): string {
  try {
    return localStorage.getItem(SERVER_BASE_KEY) ?? ''
  } catch {
    return ''
  }
}

/** True once the user explicitly configured a server (setup wizard done). */
export function isServerConfigured(): boolean {
  try {
    return localStorage.getItem(SERVER_CONFIGURED_KEY) === '1'
  } catch {
    return false
  }
}

/** Normalize + persist; returns the normalized value. */
export function setServerBase(raw: string): string {
  const normalized = normalizeServerBase(raw)
  try {
    localStorage.setItem(SERVER_BASE_KEY, normalized)
  } catch {
    // Storage unavailable — keep the in-memory value for this page life.
  }
  serverBase.value = normalized
  return normalized
}

/** axios baseURL: absolute `${base}/api/v1` or the same-origin default. */
export function apiBaseUrl(): string {
  const base = getServerBase()
  return base ? `${base}/api/v1` : '/api/v1'
}

/**
 * Prefix an endpoint path (leading '/', without the /api/v1 prefix) with the
 * configured server base. `''` base reproduces the legacy literal exactly.
 */
export function resolveApiUrl(path: string): string {
  const base = getServerBase()
  return base ? `${base}/api/v1${path}` : `/api/v1${path}`
}

/** SockJS endpoint (http(s) URL — SockJS negotiates the ws upgrade itself). */
export function wsUrl(): string {
  const base = getServerBase()
  return base ? `${base}/ws` : '/ws'
}

/** Reactive mirror of the persisted base (UI display only; switch = reload). */
export const serverBase = ref(getServerBase())
