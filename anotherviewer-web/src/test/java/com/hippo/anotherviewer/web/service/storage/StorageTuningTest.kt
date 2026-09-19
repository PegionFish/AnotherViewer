package com.hippo.anotherviewer.web.service.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageTuningTest {

    // ── 行为矩阵（设计文档 §二）───────────────────────────────────────────────

    @Test
    fun `ssd matrix no gate no prefetch high scrub normal priority`() {
        val t = StorageTuning.forProfile(StorageProfile.SSD, null)
        assertEquals(Int.MAX_VALUE, t.poolReadConcurrencyLimit)
        assertFalse(t.pageTurnPrefetchEnabled)
        assertEquals(400, t.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.NORMAL, t.maintenanceIoPriority)
        assertEquals(StorageProfile.SSD, t.profile)
    }

    @Test
    fun `hdd matrix gate 3 prefetch 60 idle`() {
        val t = StorageTuning.forProfile(StorageProfile.HDD, null)
        assertEquals(3, t.poolReadConcurrencyLimit)
        assertTrue(t.pageTurnPrefetchEnabled)
        assertEquals(60, t.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.IDLE, t.maintenanceIoPriority)
    }

    @Test
    fun `zfs matrix gate 8 conservative and 16 with cache hint`() {
        // 无 hint（null）与确认无 cache（false）都走保守 8
        assertEquals(8, StorageTuning.forProfile(StorageProfile.ZFS, null).poolReadConcurrencyLimit)
        assertEquals(8, StorageTuning.forProfile(StorageProfile.ZFS, false).poolReadConcurrencyLimit)
        // 有 cache/special vdev → 16
        assertEquals(16, StorageTuning.forProfile(StorageProfile.ZFS, true).poolReadConcurrencyLimit)

        for (hint in listOf(null, false, true)) {
            val t = StorageTuning.forProfile(StorageProfile.ZFS, hint)
            assertTrue(t.pageTurnPrefetchEnabled)
            assertEquals(120, t.scrubRateLimitMbPerSec)
            assertEquals(StorageTuning.IoPriority.IDLE, t.maintenanceIoPriority)
        }
    }

    @Test
    fun `unknown matrix matches conservative hdd params`() {
        val t = StorageTuning.forProfile(StorageProfile.UNKNOWN, null)
        assertEquals(3, t.poolReadConcurrencyLimit)
        assertTrue(t.pageTurnPrefetchEnabled)
        assertEquals(60, t.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.IDLE, t.maintenanceIoPriority)
        assertEquals(StorageProfile.UNKNOWN, t.profile)
    }

    // ── 参数 bean 跟随探测结果 ────────────────────────────────────────────────

    @Test
    fun `bean follows detected profile and refresh`() {
        val fake = FakeSystemFilesForTuning()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("nvme0n1", 0)
        val svc = StorageProfileService(fake)
        svc.detect("/srv/av/downloads")
        val tuning = StorageTuning(svc)

        assertEquals(StorageProfile.SSD, tuning.profile)
        assertEquals(Int.MAX_VALUE, tuning.poolReadConcurrencyLimit)
        assertFalse(tuning.pageTurnPrefetchEnabled)
        assertEquals(400, tuning.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.NORMAL, tuning.maintenanceIoPriority)

        // 探测翻转为带 cache 的 ZFS → refresh 后参数跟上
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/ds"))
        fake.executables.add("zpool")
        fake.execOutputs["/usr/sbin/zpool status -p tank"] = ZPOOL_WITH_CACHE
        svc.detect("/srv/av/downloads")
        tuning.refresh()

        assertEquals(StorageProfile.ZFS, tuning.profile)
        assertEquals(16, tuning.poolReadConcurrencyLimit)
        assertTrue(tuning.pageTurnPrefetchEnabled)
        assertEquals(120, tuning.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.IDLE, tuning.maintenanceIoPriority)
        assertEquals(16, tuning.current().poolReadConcurrencyLimit)
    }

    @Test
    fun `bean defaults to conservative unknown params before any probe`() {
        val tuning = StorageTuning(StorageProfileService(FakeSystemFilesForTuning()))
        assertEquals(StorageProfile.UNKNOWN, tuning.profile)
        assertEquals(3, tuning.poolReadConcurrencyLimit)
        assertTrue(tuning.pageTurnPrefetchEnabled)
        assertEquals(60, tuning.scrubRateLimitMbPerSec)
        assertEquals(StorageTuning.IoPriority.IDLE, tuning.maintenanceIoPriority)
    }

    // ── 伪造观测面（与 StorageProfileServiceTest 同构，独立小份避免互相依赖）──

    private class FakeSystemFilesForTuning : SystemFiles {
        val files = mutableMapOf<String, String>()
        val dirs = mutableMapOf<String, MutableList<String>>()
        val fsTypes = mutableMapOf<String, String>()
        val executables = mutableSetOf<String>()
        val execOutputs = mutableMapOf<String, String>()

        override fun readFile(path: String): String? = files[path]

        /** 目录存在性由已知子条目推导（有 path/ 前缀的孩子即视为存在）；显式 dirs 条目优先。 */
        override fun listDir(path: String): List<String>? {
            dirs[path]?.let { return it.toList() }
            val prefix = "$path/"
            val hasChildren = files.keys.any { it.startsWith(prefix) } || dirs.keys.any { it.startsWith(prefix) }
            return if (hasChildren) emptyList() else null
        }
        override fun findExecutable(name: String): String? = if (name in executables) "/usr/sbin/$name" else null
        override fun exec(command: List<String>, timeoutMs: Long): String? = execOutputs[command.joinToString(" ")]
        override fun fileStoreType(path: String): String? = fsTypes[path]

        fun disk(name: String, rotational: Int) {
            files["/sys/block/$name/queue/rotational"] = rotational.toString()
        }

        fun mountAll(vararg lines: String) {
            files["/proc/self/mountinfo"] = lines.joinToString("\n") + "\n"
        }
    }

    private fun mi(mountPoint: String, fsType: String, source: String): String =
        "42 1 8:17 / $mountPoint rw,relatime - $fsType $source rw"

    private companion object {
        private val ZPOOL_WITH_CACHE = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0

            cache
            	  /dev/nvme0n1 -          0     0     0
        """.trimIndent()
    }
}
