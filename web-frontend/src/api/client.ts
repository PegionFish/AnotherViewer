import axios, { type InternalAxiosRequestConfig } from 'axios'
import { markDown, EH_UNAVAILABLE_MESSAGE } from '@/stores/availability'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

/**
 * AbortControllers of in-flight requests (audit P2). A 401 means the session
 * is dead: every other pending request would 401 the same way, so they are
 * swept (aborted) instead of trickling in one error each. Controllers are
 * keyed off the request config via a WeakMap (no config mutation, GC-friendly).
 */
const liveControllers = new Set<AbortController>()
const controllerOfConfig = new WeakMap<InternalAxiosRequestConfig, AbortController>()

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  // Track the request for the 401 sweep unless the caller manages its own
  // cancellation (axios honours signals attached in request interceptors).
  if (!config.signal) {
    const controller = new AbortController()
    config.signal = controller.signal
    controllerOfConfig.set(config, controller)
    liveControllers.add(controller)
  }
  return config
})

/** Drop a settled request's controller from the in-flight set. */
function releaseInflight(config: InternalAxiosRequestConfig | undefined): void {
  const controller = config && controllerOfConfig.get(config)
  if (controller) {
    controllerOfConfig.delete(config)
    liveControllers.delete(controller)
  }
}

/** Abort every in-flight request (session-death sweep on 401). */
function cancelInflight(): void {
  for (const controller of liveControllers) controller.abort()
  liveControllers.clear()
}

/**
 * Session-death handling (audit P2): clear credentials and route to Login
 * keeping the current location in `?redirect=` (SPA navigation instead of the
 * old hard `window.location.href` reload). The flag collapses a burst of
 * concurrent 401s into a single redirect; it releases once navigation settles
 * so a later session expiry redirects again. The router is imported lazily —
 * a static import would make every view spec that mocks 'vue-router' crash at
 * load time (views transitively import this module).
 */
let redirectingToLogin = false

function handleUnauthorized(): void {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  cancelInflight()
  if (redirectingToLogin) return
  redirectingToLogin = true
  void import('@/router')
    .then(({ default: router }) => {
      const current = router.currentRoute.value
      // Already sitting on Login (e.g. a stray expired-session request while
      // on the login screen): no self-redirect, no pointless redirect query.
      if (current.name === 'Login') return undefined
      return router.replace({ name: 'Login', query: { redirect: current.fullPath } })
    })
    .catch(() => {
      // Navigation can legitimately fail during teardown — never global-error.
    })
    .finally(() => {
      redirectingToLogin = false
    })
}

/** Thrown when the service worker reports offline with no cached data. */
export class OfflineError extends Error {
  constructor() {
    super('offline')
    this.name = 'OfflineError'
  }
}

/** Type guard for {@link OfflineError}; views use it to show a friendly offline tip. */
export function isOfflineError(error: unknown): error is OfflineError {
  return error instanceof OfflineError
}

/**
 * True when the service worker reported offline with nothing cached:
 * HTTP 503 and a payload carrying the `offline` marker (sw.js answers
 * `{error:'offline', message:'No cached data available'}` without a
 * JSON Content-Type, so the payload may arrive as object or string).
 */
export function isOfflinePayload(status: number | undefined, data: unknown): boolean {
  if (status !== 503) return false
  if (typeof data === 'object' && data !== null) {
    return (data as { error?: string }).error === 'offline'
  }
  return typeof data === 'string' && data.includes('offline')
}

/**
 * Error code of the server error envelope (`{error:{code,message,…}}`, plan
 * §4.1) — same extraction as the views' `errorCodeOf` helpers.
 */
export function errorCodeOf(error: unknown): string | null {
  const code = (error as { response?: { data?: { error?: { code?: unknown } } } } | undefined)
    ?.response?.data?.error?.code
  return typeof code === 'string' ? code : null
}

/**
 * The server's circuit-breaker answered "EH platform unreachable" (HTTP 404 +
 * `EH_UNAVAILABLE`, see plan-2026-08-30 §3.2). Views use this to stop auto
 * retries / switch to the local-only tip; the availability store is marked
 * DOWN by the response interceptor below.
 */
export class EhUnavailableError extends Error {
  constructor(message: string = EH_UNAVAILABLE_MESSAGE) {
    super(message)
    this.name = 'EhUnavailableError'
  }
}

/**
 * Type guard for {@link EhUnavailableError}. Also accepts the raw
 * axios-shaped error (envelope code check) so mocked/thrown list-envelope
 * errors (success:false + cause) are recognized by the same predicate.
 */
export function isEhUnavailableError(error: unknown): error is EhUnavailableError {
  return error instanceof EhUnavailableError || errorCodeOf(error) === 'EH_UNAVAILABLE'
}

client.interceptors.response.use(
  (response) => {
    releaseInflight(response.config)
    return response
  },
  (error) => {
    releaseInflight(error?.config)
    // Session death: clear credentials, cancel the other in-flight requests
    // and route to Login with a redirect query (handleUnauthorized above).
    if (error.response?.status === 401) {
      handleUnauthorized()
    }
    // The service worker answers 503 + {error:'offline'} when offline and
    // nothing is cached (sw.js). Surface it as a typed error so views can
    // distinguish "offline, no cache" from a plain network/server failure.
    if (isOfflinePayload(error.response?.status, error.response?.data)) {
      return Promise.reject(new OfflineError())
    }
    // Circuit-breaker: the server short-circuited an EH upstream call
    // (404 EH_UNAVAILABLE). Mark DOWN immediately (views/banner react without
    // waiting for the next /site/availability load) and surface a typed error
    // so callers stop auto-retrying.
    if (errorCodeOf(error) === 'EH_UNAVAILABLE') {
      const message =
        typeof error.response?.data?.error?.message === 'string'
          ? error.response.data.error.message
          : EH_UNAVAILABLE_MESSAGE
      markDown(message)
      return Promise.reject(new EhUnavailableError(message))
    }
    return Promise.reject(error)
  }
)

export default client
