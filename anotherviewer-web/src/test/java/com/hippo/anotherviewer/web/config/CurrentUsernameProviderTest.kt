package com.hippo.anotherviewer.web.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * A7-1: [CurrentUsernameProvider] 是 Web 本地写 stamping 的唯一取值点。
 * 空 context（worker/@Scheduled 线程）回落 "default"；有认证上下文返回登录名。
 */
class CurrentUsernameProviderTest {

    private val provider = CurrentUsernameProvider()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `empty security context falls back to default`() {
        assertEquals("default", provider.currentUsername())
    }

    @Test
    fun `authenticated username is returned`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            "alice", null, listOf(SimpleGrantedAuthority("ROLE_USER"))
        )

        assertEquals("alice", provider.currentUsername())
    }
}
