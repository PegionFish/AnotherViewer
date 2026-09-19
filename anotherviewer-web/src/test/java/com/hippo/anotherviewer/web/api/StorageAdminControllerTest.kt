package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.config.GlobalExceptionHandler
import com.hippo.anotherviewer.web.service.storage.StorageProfileService
import com.hippo.anotherviewer.web.service.storage.StorageTuning
import com.hippo.anotherviewer.web.service.storage.SystemFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * [StorageAdminController] 端点切片测试（存储自适配 P3）。
 *
 * 控制器无仓库依赖，直接手工装配：真实 [StorageProfileService]（注入伪造
 * mountinfo/sys 文本树——绝不读真实系统）+ 真实 [StorageTuning] + standalone
 * MockMvc（照 IntegrityControllerTest 的 advice 接线）。重点断言：端点对 P1
 * 契约的透传（结果/证据/参数三块全量出 JSON）、ZFS 建议清单的形态条件性、
 * redetect 后 tuning 的 refresh 联动、以及「从未探测过」时的下载根回退。
 */
class StorageAdminControllerTest {

    // ── 伪造观测面（同 P1 StorageProfileServiceTest 的最小版） ─────────────────

    private class FakeSystemFiles : SystemFiles {
        val files = mutableMapOf<String, String>()
        val dirs = mutableMapOf<String, MutableList<String>>()
        val fsTypes = mutableMapOf<String, String>()
        val executables = mutableSetOf<String>()
        val execOutputs = mutableMapOf<String, String>()

        override fun readFile(path: String): String? = files[path]

        override fun listDir(path: String): List<String>? {
            dirs[path]?.let { return it.toList() }
            val prefix = "$path/"
            val hasChildren = files.keys.any { it.startsWith(prefix) } || dirs.keys.any { it.startsWith(prefix) }
            return if (hasChildren) emptyList() else null
        }

        override fun findExecutable(name: String): String? = if (name in executables) "/usr/sbin/$name" else null
        override fun exec(command: List<String>, timeoutMs: Long): String? = execOutputs[command.joinToString(" ")]
        override fun fileStoreType(path: String): String? = fsTypes[path]
    }

    private fun FakeSystemFiles.disk(name: String, rotational: Int) {
        files["/sys/block/$name/queue/rotational"] = rotational.toString()
    }

    private fun FakeSystemFiles.mountAll(vararg lines: String) {
        files["/proc/self/mountinfo"] = lines.joinToString("\n") + "\n"
    }

    /** mountinfo 行：id parent major:minor root mount-point opts - fs-type source super-opts */
    private fun mi(mountPoint: String, fsType: String, source: String): String =
        "42 1 8:17 / $mountPoint rw,relatime - $fsType $source rw"

    /** 全机一块 NVMe SSD（/srv/av 覆盖 downloads/cache/db）。 */
    private fun fakeSsd(): FakeSystemFiles = FakeSystemFiles().apply {
        mountAll(mi("/srv/av", "ext4", "/dev/nvme0n1p2"))
        fsTypes["/srv/av"] = "ext4"
        fsTypes["/srv/av/downloads"] = "ext4"
        disk("nvme0n1", 0)
    }

    private fun fakeZfs(withCache: Boolean = true): FakeSystemFiles = FakeSystemFiles().apply {
        mountAll(mi("/srv/av", "zfs", "tank/downloads"))
        fsTypes["/srv/av"] = "zfs"
        executables.add("zpool")
        execOutputs["/usr/sbin/zpool status -p tank"] = if (withCache) ZPOOL_WITH_CACHE else ZPOOL_PLAIN
    }

    private fun mvc(
        fake: FakeSystemFiles,
        downloadPath: String = "/srv/av/downloads",
        override: String = "auto",
    ): MockMvc {
        val service = StorageProfileService(
            systemFiles = fake,
            profileOverride = override,
            configuredCachePath = "/srv/av/cache",
            configuredDatasourceUrl = "jdbc:sqlite:/srv/av/data/anotherviewer.db",
        )
        val controller = StorageAdminController(service, StorageTuning(service), downloadPath)
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    // ── GET /profile：SSD 正常态 ──────────────────────────────────────────────

    @Test
    fun `profile on fresh service probes configured download path and reports full ssd payload`() {
        val result = mvc(fakeSsd()).perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("SSD"))
            // 证据明细全量透传（管理页 evidence 卡的数据源）
            .andExpect(jsonPath("$.profile.evidence.rootPath").value("/srv/av/downloads"))
            .andExpect(jsonPath("$.profile.evidence.fsType").value("ext4"))
            .andExpect(jsonPath("$.profile.evidence.fsTypeSource").value("file-store"))
            .andExpect(jsonPath("$.profile.evidence.mountSource").value("/dev/nvme0n1p2"))
            .andExpect(jsonPath("$.profile.evidence.leafDevices[0]").value("nvme0n1"))
            .andExpect(jsonPath("$.profile.evidence.rotational.nvme0n1").value(0))
            .andExpect(jsonPath("$.profile.evidence.failureReason").doesNotExist())
            .andExpect(jsonPath("$.profile.evidence.overrideApplied").doesNotExist())
            // SSD 自适应参数（行为矩阵）
            .andExpect(jsonPath("$.tuning.profile").value("SSD"))
            .andExpect(jsonPath("$.tuning.poolReadConcurrencyLimit").value(Int.MAX_VALUE))
            .andExpect(jsonPath("$.tuning.pageTurnPrefetchEnabled").value(false))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(400))
            .andExpect(jsonPath("$.tuning.maintenanceIoPriority").value("NORMAL"))
            // 部署不变量：cache/db 两腿均 SSD
            .andExpect(jsonPath("$.deployment.invariantHeld").value(true))
            .andExpect(jsonPath("$.deployment.cache.path").value("/srv/av/cache"))
            .andExpect(jsonPath("$.deployment.cache.profile").value("SSD"))
            .andExpect(jsonPath("$.deployment.cache.compliant").value(true))
            .andExpect(jsonPath("$.deployment.db.path").value("/srv/av/data/anotherviewer.db"))
            .andExpect(jsonPath("$.deployment.db.compliant").value(true))
            // 非 ZFS：建议清单为空
            .andExpect(jsonPath("$.zfsRecommendations").isEmpty())
            .andReturn()

        assertEquals(200, result.response.status)
    }

    // ── GET /profile：ZFS 建议清单 ────────────────────────────────────────────

    @Test
    fun `profile returns zfs recommendations and zfs tuning only for zfs profile`() {
        mvc(fakeZfs(withCache = true)).perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("ZFS"))
            .andExpect(jsonPath("$.profile.evidence.zfsPool").value("tank"))
            .andExpect(jsonPath("$.profile.evidence.zfsCacheHint").value(true))
            // 有 cache hint → 读并发 16、巡检限速 120、预读开、维护 IDLE
            .andExpect(jsonPath("$.tuning.profile").value("ZFS"))
            .andExpect(jsonPath("$.tuning.poolReadConcurrencyLimit").value(16))
            .andExpect(jsonPath("$.tuning.pageTurnPrefetchEnabled").value(true))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(120))
            .andExpect(jsonPath("$.tuning.maintenanceIoPriority").value("IDLE"))
            .andExpect(jsonPath("$.zfsRecommendations.length()").value(6))
            .andExpect(jsonPath("$.zfsRecommendations[0].id").value("recordsize"))
            .andExpect(jsonPath("$.zfsRecommendations[0].title").isNotEmpty)
            .andExpect(jsonPath("$.zfsRecommendations[0].description").isNotEmpty)
            .andExpect(jsonPath("$.zfsRecommendations[1].id").value("special-vdev"))
            .andExpect(jsonPath("$.zfsRecommendations[2].id").value("compression"))
            .andExpect(jsonPath("$.zfsRecommendations[3].id").value("arc-max"))
            .andExpect(jsonPath("$.zfsRecommendations[4].id").value("l2arc"))
            .andExpect(jsonPath("$.zfsRecommendations[5].id").value("fs-tweaks"))
    }

    @Test
    fun `zfs tuning falls back to conservative concurrency without cache hint`() {
        mvc(fakeZfs(withCache = false)).perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            // hint=false = zpool 解析「确认无」加速卡（P1 语义；null 才是探测失败）
            .andExpect(jsonPath("$.profile.evidence.zfsCacheHint").value(false))
            .andExpect(jsonPath("$.tuning.poolReadConcurrencyLimit").value(8))
    }

    // ── GET /profile：部署不变量违例 vs 不可验证 ───────────────────────────────

    @Test
    fun `profile reports deployment invariant violation with per-leg profile and evidence`() {
        val fake = FakeSystemFiles().apply {
            mountAll(
                mi("/srv/av", "ext4", "/dev/nvme0n1p2"), // downloads/cache 在 SSD
                mi("/mnt/hdd", "ext4", "/dev/sdb1"),     // db 在机械盘 → 违例
            )
            fsTypes["/srv/av"] = "ext4"
            fsTypes["/mnt/hdd"] = "ext4"
            disk("nvme0n1", 0)
            disk("sdb", 1)
        }
        val service = StorageProfileService(
            systemFiles = fake,
            configuredCachePath = "/srv/av/cache",
            configuredDatasourceUrl = "jdbc:sqlite:/mnt/hdd/anotherviewer.db",
        )
        val controller = StorageAdminController(service, StorageTuning(service), "/srv/av/downloads")
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        mockMvc.perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("SSD"))
            .andExpect(jsonPath("$.deployment.invariantHeld").value(false))
            .andExpect(jsonPath("$.deployment.cache.compliant").value(true))
            .andExpect(jsonPath("$.deployment.db.path").value("/mnt/hdd/anotherviewer.db"))
            .andExpect(jsonPath("$.deployment.db.profile").value("HDD"))
            .andExpect(jsonPath("$.deployment.db.compliant").value(false))
            // 违例腿带证据（黄牌详情用：哪块盘、回转标志）
            .andExpect(jsonPath("$.deployment.db.evidence.leafDevices[0]").value("sdb"))
            .andExpect(jsonPath("$.deployment.db.evidence.rotational.sdb").value(1))
    }

    @Test
    fun `profile marks network fs deployment as unverifiable unknown rather than violation`() {
        val fake = FakeSystemFiles().apply {
            mountAll(mi("/srv/av", "nfs", "nas:/export"))
            fsTypes["/srv/av"] = "nfs"
        }
        val service = StorageProfileService(
            systemFiles = fake,
            configuredCachePath = "/srv/av/cache",
            configuredDatasourceUrl = "jdbc:sqlite:/srv/av/data/anotherviewer.db",
        )
        val controller = StorageAdminController(service, StorageTuning(service), "/srv/av/downloads")
        val mockMvc = MockMvcBuilders.standaloneSetup(controller).build()

        mockMvc.perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.deployment.invariantHeld").value(false))
            // 两腿 UNKNOWN + 不 compliant：真违例（HDD/ZFS）与不可验证（UNKNOWN）凭形态区分展示
            .andExpect(jsonPath("$.deployment.cache.profile").value("UNKNOWN"))
            .andExpect(jsonPath("$.deployment.cache.compliant").value(false))
            .andExpect(jsonPath("$.deployment.db.profile").value("UNKNOWN"))
            .andExpect(jsonPath("$.deployment.db.compliant").value(false))
    }

    // ── override 证据透传 ─────────────────────────────────────────────────────

    @Test
    fun `profile surfaces override evidence when forced`() {
        mvc(fakeSsd(), override = "hdd").perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("HDD"))
            .andExpect(jsonPath("$.profile.evidence.overrideApplied").value("HDD"))
            .andExpect(jsonPath("$.profile.evidence.naturalProfile").value("SSD"))
    }

    // ── POST /redetect：重探 + tuning refresh 联动 ────────────────────────────

    @Test
    fun `redetect re-probes last root and refreshes tuning parameters`() {
        val fake = fakeSsd()
        // 下载根单独 bind 到机械盘（/srv/av 本体仍是 NVMe → cache/db 腿合规）
        fake.mountAll(
            mi("/srv/av", "ext4", "/dev/nvme0n1p2"),
            mi("/srv/av/downloads", "ext4", "/dev/sdb1"),
        )
        fake.disk("sdb", 1)
        val mockMvc = mvc(fake)

        // 先 GET：建立 P1 缓存（下载根 HDD → 巡检限速 60）
        mockMvc.perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("HDD"))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(60))

        // 伪造树翻转：下载根变成 ZFS（无 zpool → 保守）
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/ds"))
        fake.fsTypes["/srv/av/downloads"] = "zfs"

        mockMvc.perform(post("/api/v1/admin/storage/redetect"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("ZFS"))
            .andExpect(jsonPath("$.profile.evidence.rootPath").value("/srv/av/downloads"))
            // refresh 联动：参数随新形态重算（60 → 120）
            .andExpect(jsonPath("$.tuning.profile").value("ZFS"))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(120))
            .andExpect(jsonPath("$.tuning.poolReadConcurrencyLimit").value(8))

        // GET 复读同见新形态（P1 缓存已被 redetect 更新）
        mockMvc.perform(get("/api/v1/admin/storage/profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("ZFS"))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(120))
    }

    @Test
    fun `redetect on fresh service falls back to configured download path and caches it`() {
        val fake = fakeSsd()
        val mockMvc = mvc(fake)

        // 服务从未探测过：回退探测配置的下载根，不空转
        mockMvc.perform(post("/api/v1/admin/storage/redetect"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("SSD"))
            .andExpect(jsonPath("$.profile.evidence.rootPath").value("/srv/av/downloads"))
            .andExpect(jsonPath("$.tuning.poolReadConcurrencyLimit").value(Int.MAX_VALUE))

        // 回退已入缓存：随后改动只落在下载根上也会被重探命中（root 固定为回退值）
        fake.mountAll(
            mi("/srv/av", "ext4", "/dev/nvme0n1p2"),
            mi("/srv/av/downloads", "ext4", "/dev/sdb1"),
        )
        fake.disk("sdb", 1)

        mockMvc.perform(post("/api/v1/admin/storage/redetect"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.profile").value("HDD"))
            .andExpect(jsonPath("$.tuning.scrubRateLimitMbPerSec").value(60))
    }

    // ── ZFS 建议清单静态结构（id 唯一、标题/说明齐备） ──────────────────────────

    @Test
    fun `zfs recommendation list is well-formed static content`() {
        val ids = StorageAdminController.ZFS_RECOMMENDATIONS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "id 必须唯一（前端 key）")
        StorageAdminController.ZFS_RECOMMENDATIONS.forEach {
            assertTrue(it.title.isNotBlank() && it.description.isNotBlank(), "每项须有标题与说明: ${it.id}")
        }
        assertTrue(StorageAdminController.ZFS_RECOMMENDATIONS.isNotEmpty())
        assertNotNull(StorageAdminController.ZFS_RECOMMENDATIONS.firstOrNull { it.id == "recordsize" })
    }

    // ── zpool status 伪造输出 ─────────────────────────────────────────────────

    private companion object {
        private val ZPOOL_WITH_CACHE = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0

            cache
            	  /dev/nvme0n1 -          0     0     0
        """.trimIndent()

        private val ZPOOL_PLAIN = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0
        """.trimIndent()
    }
}
