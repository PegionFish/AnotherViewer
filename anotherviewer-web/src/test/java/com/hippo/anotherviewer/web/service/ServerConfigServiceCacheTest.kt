package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import com.hippo.anotherviewer.web.entity.ServerConfigEntity
import com.hippo.anotherviewer.web.repository.ServerConfigRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave1 V2：ServerConfigService 60s 单键缓存。AuthTokenFilter 与
 * PrivacyMaskFilter 每请求各读一次 server_config，缓存后读路径必须命中内存
 * （mock repository 计数），写操作立即失效。仓库交互保持原形态（findById 读
 * / save 写），与各处测试替身、真实调用方一致。
 */
class ServerConfigServiceCacheTest {

    private lateinit var repo: ServerConfigRepository
    private lateinit var service: ServerConfigService
    private val store = ConcurrentHashMap<String, ServerConfigEntity>()

    @BeforeEach
    fun setUp() {
        store.clear()
        repo = mock(ServerConfigRepository::class.java)
        `when`(repo.findById(anyString())).thenAnswer { inv ->
            Optional.ofNullable(store[inv.getArgument<String>(0)])
        }
        `when`(repo.save(any(ServerConfigEntity::class.java))).thenAnswer { inv ->
            val e = inv.getArgument<ServerConfigEntity>(0)
            store[e.key] = e
            e
        }
        `when`(repo.delete(any(ServerConfigEntity::class.java))).thenAnswer { inv ->
            store.remove(inv.getArgument<ServerConfigEntity>(0).key)
            null
        }
        service = ServerConfigService(repo, mock(EncryptionService::class.java), SiteCoreConfigProperties())
    }

    private fun put(key: String, value: String) {
        store[key] = ServerConfigEntity().apply { this.key = key; this.value = value }
    }

    @Test
    fun `repeated reads within ttl hit the database only once`() {
        put(ServerConfigService.KEY_PRIVACY_MASK, "true")
        put("plain", "v1")

        // 首读 + 二读、get/getBoolean 多形态：全部走同一份缓存。
        assertEquals("v1", service.get("plain"))
        assertEquals("v1", service.get("plain", ""))
        assertEquals(true, service.getBoolean(ServerConfigService.KEY_PRIVACY_MASK))
        assertEquals(true, service.getBoolean(ServerConfigService.KEY_PRIVACY_MASK, false))

        verify(repo, times(1)).findById("plain")
        verify(repo, times(1)).findById(ServerConfigService.KEY_PRIVACY_MASK)
    }

    @Test
    fun `missing keys are negative-cached and fall back to default`() {
        assertEquals("fallback", service.get("absent", "fallback"))
        assertEquals("fallback", service.get("absent", "fallback"))
        assertEquals(7L, service.getLong("absent", 7L))

        verify(repo, times(1)).findById("absent")
    }

    @Test
    fun `set invalidates the cache so the next read reloads from the database`() {
        put("plain", "old")
        assertEquals("old", service.get("plain"))

        // 写路径本身也要 findById 一次（读改写），写完失效 → 下一次读再查库。
        service.set("plain", "new")

        assertEquals("new", service.get("plain"))
        verify(repo, times(3)).findById("plain")
    }

    @Test
    fun `set on a fresh key also invalidates`() {
        assertEquals("", service.get("fresh"))

        service.set("fresh", "1")

        assertEquals("1", service.get("fresh"))
        verify(repo, times(3)).findById("fresh")
    }

    @Test
    fun `delete invalidates the cache`() {
        put("plain", "v")
        assertEquals("v", service.get("plain"))

        service.delete(ServerConfigEntity().apply { key = "plain"; value = "v" })

        assertEquals("gone", service.get("plain", "gone"))
        verify(repo, times(2)).findById("plain")
    }

    @Test
    fun `setBoolean keeps reads consistent after invalidation`() {
        put(ServerConfigService.KEY_PRIVACY_MASK, "false")
        assertEquals(false, service.getBoolean(ServerConfigService.KEY_PRIVACY_MASK))

        service.setBoolean(ServerConfigService.KEY_PRIVACY_MASK, true)

        assertEquals(true, service.getBoolean(ServerConfigService.KEY_PRIVACY_MASK))
        verify(repo, times(3)).findById(ServerConfigService.KEY_PRIVACY_MASK)
    }
}
