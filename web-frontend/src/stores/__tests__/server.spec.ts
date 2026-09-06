import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  SERVER_BASE_KEY,
  SERVER_CONFIGURED_KEY,
  ServerBaseError,
  apiBaseUrl,
  getServerBase,
  isServerConfigured,
  normalizeServerBase,
  resolveApiUrl,
  serverBase,
  setServerBase,
  wsUrl,
} from '../server'

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('normalizeServerBase 输入矩阵（plan-2026-09-06-pwa §3.1 冻结契约）', () => {
  it("'' stays '' (same-origin)", () => {
    expect(normalizeServerBase('')).toBe('')
  })

  it('prepends http:// to scheme-less host:port', () => {
    expect(normalizeServerBase('192.168.6.141:8081')).toBe('http://192.168.6.141:8081')
  })

  it('prepends http:// to a bare host', () => {
    expect(normalizeServerBase('host')).toBe('http://host')
  })

  it('strips the trailing slash', () => {
    expect(normalizeServerBase('http://host:8081/')).toBe('http://host:8081')
  })

  it('keeps only the origin (drops path)', () => {
    expect(normalizeServerBase('http://host:8081/sub/path')).toBe('http://host:8081')
  })

  it('preserves an explicit https scheme', () => {
    expect(normalizeServerBase('https://host')).toBe('https://host')
  })

  it('rejects non-http(s) schemes with ServerBaseError', () => {
    expect(() => normalizeServerBase('ftp://host')).toThrow(ServerBaseError)
  })

  it('rejects unparseable input with ServerBaseError', () => {
    expect(() => normalizeServerBase('not a url')).toThrow(ServerBaseError)
  })
})

describe('同源零回归锚点：无 server-base 键时与旧硬编码逐字节一致', () => {
  it('apiBaseUrl() is the legacy literal /api/v1', () => {
    expect(localStorage.getItem(SERVER_BASE_KEY)).toBeNull()
    expect(apiBaseUrl()).toBe('/api/v1')
  })

  it('wsUrl() is the legacy literal /ws', () => {
    expect(wsUrl()).toBe('/ws')
  })

  it('resolveApiUrl() reproduces the legacy /api/v1 prefix (query included)', () => {
    expect(resolveApiUrl('/image/proxy?url=x')).toBe('/api/v1/image/proxy?url=x')
  })

  it('getServerBase() defaults to empty and isServerConfigured() to false', () => {
    expect(getServerBase()).toBe('')
    expect(isServerConfigured()).toBe(false)
  })
})

describe('setServerBase：归一化 + 持久化 + 派生 URL', () => {
  it('persists the normalized base and re-derives api/ws/resolve URLs', () => {
    const normalized = setServerBase('192.168.6.141:8081')

    expect(normalized).toBe('http://192.168.6.141:8081')
    expect(apiBaseUrl()).toBe('http://192.168.6.141:8081/api/v1')
    expect(wsUrl()).toBe('http://192.168.6.141:8081/ws')
    expect(resolveApiUrl('/image/proxy?url=x')).toBe(
      'http://192.168.6.141:8081/api/v1/image/proxy?url=x'
    )
  })

  it('syncs the serverBase ref and getServerBase() reads the persisted value', () => {
    setServerBase('192.168.6.141:8081')

    expect(serverBase.value).toBe('http://192.168.6.141:8081')
    expect(getServerBase()).toBe('http://192.168.6.141:8081')
    expect(localStorage.getItem(SERVER_BASE_KEY)).toBe('http://192.168.6.141:8081')
  })

  it("'' resets back to same-origin", () => {
    setServerBase('http://host:8081')
    expect(setServerBase('')).toBe('')
    expect(getServerBase()).toBe('')
    expect(apiBaseUrl()).toBe('/api/v1')
  })

  it('rejects invalid input without persisting anything', () => {
    expect(() => setServerBase('ftp://host')).toThrow(ServerBaseError)
    expect(localStorage.getItem(SERVER_BASE_KEY)).toBeNull()
    expect(getServerBase()).toBe('')
  })
})

describe('isServerConfigured：显式配置标记', () => {
  it('is false by default and for values other than 1', () => {
    expect(isServerConfigured()).toBe(false)
    localStorage.setItem(SERVER_CONFIGURED_KEY, '0')
    expect(isServerConfigured()).toBe(false)
  })

  it("is true once the key is stamped '1'", () => {
    localStorage.setItem(SERVER_CONFIGURED_KEY, '1')
    expect(isServerConfigured()).toBe(true)
  })
})

describe('localStorage 异常容错', () => {
  /**
   * Swap the global storage for one that throws on every access. (happy-dom's
   * Storage is a Proxy that copies prototype methods onto the instance on
   * first access, so spying on Storage.prototype is order-dependent —
   * replacing the global is deterministic.)
   */
  function breakStorage(): void {
    const thrower = () => {
      throw new Error('storage broken')
    }
    vi.stubGlobal('localStorage', {
      length: 0,
      key: thrower,
      getItem: thrower,
      setItem: thrower,
      removeItem: thrower,
      clear: thrower,
    })
  }

  it('getServerBase() falls back to empty when storage throws', () => {
    localStorage.setItem(SERVER_BASE_KEY, 'http://host:8081')
    breakStorage()

    expect(getServerBase()).toBe('')
    expect(apiBaseUrl()).toBe('/api/v1')
    expect(wsUrl()).toBe('/ws')
  })

  it('isServerConfigured() falls back to false when storage throws', () => {
    localStorage.setItem(SERVER_CONFIGURED_KEY, '1')
    breakStorage()

    expect(isServerConfigured()).toBe(false)
  })

  it('setServerBase() still normalizes and syncs the ref when persisting throws', () => {
    breakStorage()

    expect(setServerBase('host:8081')).toBe('http://host:8081')
    expect(serverBase.value).toBe('http://host:8081')
  })
})
