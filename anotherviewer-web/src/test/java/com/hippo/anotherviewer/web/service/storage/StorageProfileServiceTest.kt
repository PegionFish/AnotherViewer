package com.hippo.anotherviewer.web.service.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 全部用伪造 mountinfo/sys 文本树注入（FakeSystemFiles），绝不读真实系统——
 * macOS 本机没有 /proc、/sys，主代码也只经 SystemFiles 拿数据。
 */
class StorageProfileServiceTest {

    // ── 伪造观测面 ────────────────────────────────────────────────────────────

    private class FakeSystemFiles : SystemFiles {
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

    private fun newService(
        fake: FakeSystemFiles,
        override: String = "auto",
        cachePath: String = "/srv/av/cache",
        datasourceUrl: String = "jdbc:sqlite:/srv/av/data/anotherviewer.db?journal_mode=WAL&busy_timeout=30000",
    ) = StorageProfileService(
        systemFiles = fake,
        profileOverride = override,
        configuredCachePath = cachePath,
        configuredDatasourceUrl = datasourceUrl,
    )

    // ── 3. 块设备回转分析 ─────────────────────────────────────────────────────

    @Test
    fun `plain ssd direct disk detected as ssd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("nvme0n1", 0)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.SSD, r.profile)
        assertNull(r.evidence.failureReason)
        assertEquals("ext4", r.evidence.fsType)
        assertEquals("file-store", r.evidence.fsTypeSource)
        assertEquals("/dev/nvme0n1p2", r.evidence.mountSource)
        assertEquals(listOf("nvme0n1"), r.evidence.leafDevices)
        assertEquals(mapOf("nvme0n1" to 0), r.evidence.rotational)
    }

    @Test
    fun `rotational disk detected as hdd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/sdb1"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("sdb", 1)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertNull(r.evidence.failureReason)
        assertEquals(listOf("sdb"), r.evidence.leafDevices)
        assertEquals(mapOf("sdb" to 1), r.evidence.rotational)
    }

    @Test
    fun `luks over lvm slaves recursion reaches underlying disks`() {
        val fake = FakeSystemFiles()
        // /dev/mapper/luks-abc123 (dm-1) → dm-0 (LVM vg0-lv0) → sda1 + sdb1，两块机械盘
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/mapper/luks-abc123"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.dirs["/sys/block"] = mutableListOf("dm-0", "dm-1", "sda", "sdb")
        fake.files["/sys/block/dm-1/dm/name"] = "luks-abc123"
        fake.files["/sys/block/dm-0/dm/name"] = "vg0-lv0"
        fake.dirs["/sys/block/dm-1/slaves"] = mutableListOf("dm-0")
        fake.dirs["/sys/block/dm-0/slaves"] = mutableListOf("sda1", "sdb1")
        fake.disk("sda", 1)
        fake.disk("sdb", 1)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertNull(r.evidence.failureReason)
        assertEquals(setOf("sda", "sdb"), r.evidence.leafDevices.toSet())
        assertEquals(mapOf("sda" to 1, "sdb" to 1), r.evidence.rotational)
    }

    @Test
    fun `stacked devices all rotational zero detected as ssd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/mapper/vg0-lv0"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.dirs["/sys/block"] = mutableListOf("dm-0", "nvme0n1", "nvme1n1")
        fake.files["/sys/block/dm-0/dm/name"] = "vg0-lv0"
        fake.dirs["/sys/block/dm-0/slaves"] = mutableListOf("nvme0n1p1", "nvme1n1p1")
        fake.disk("nvme0n1", 0)
        fake.disk("nvme1n1", 0)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.SSD, r.profile)
        assertNull(r.evidence.failureReason)
    }

    @Test
    fun `mixed pool short board decides hdd`() {
        val fake = FakeSystemFiles()
        // LVM 条带：一块 NVMe + 一块机械盘 → 索引/写放大按短板 HDD 处理
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/mapper/vg0-lv0"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.dirs["/sys/block"] = mutableListOf("dm-0", "sdc", "sdd")
        fake.files["/sys/block/dm-0/dm/name"] = "vg0-lv0"
        fake.dirs["/sys/block/dm-0/slaves"] = mutableListOf("sdc1", "sdd1")
        fake.disk("sdc", 0)
        fake.disk("sdd", 1)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertNull(r.evidence.failureReason)
        assertEquals(setOf("sdc", "sdd"), r.evidence.leafDevices.toSet())
    }

    // ── 1. ZFS ───────────────────────────────────────────────────────────────

    @Test
    fun `zfs fs type yields zfs profile with cache hint`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/datasets/downloads"))
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        fake.executables.add("zpool")
        fake.execOutputs["/usr/sbin/zpool status -p tank"] = ZPOOL_WITH_CACHE

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.ZFS, r.profile)
        assertEquals("tank", r.evidence.zfsPool)
        assertEquals(true, r.evidence.zfsCacheHint)
        assertNull(r.evidence.failureReason)
    }

    @Test
    fun `zfs with special vdev also counts as accelerator hint`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/downloads"))
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        fake.executables.add("zpool")
        fake.execOutputs["/usr/sbin/zpool status -p tank"] = ZPOOL_WITH_SPECIAL

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.ZFS, r.profile)
        assertEquals(true, r.evidence.zfsCacheHint)
    }

    @Test
    fun `zfs without accelerator sections yields hint false`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/downloads"))
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        fake.executables.add("zpool")
        fake.execOutputs["/usr/sbin/zpool status -p tank"] = ZPOOL_WITHOUT_ACCELERATOR

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.ZFS, r.profile)
        assertEquals(false, r.evidence.zfsCacheHint)
        assertNull(r.evidence.failureReason)
    }

    @Test
    fun `zfs without zpool command yields conservative zfs without hint`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/downloads"))
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        // 无 zpool 可执行文件

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.ZFS, r.profile)
        assertEquals("tank", r.evidence.zfsPool)
        assertNull(r.evidence.zfsCacheHint)
        assertNotNull(r.evidence.failureReason)
    }

    @Test
    fun `zpool accelerator section parser`() {
        assertTrue(StorageProfileService.parseZpoolAcceleratorSections(ZPOOL_WITH_CACHE))
        assertTrue(StorageProfileService.parseZpoolAcceleratorSections(ZPOOL_WITH_SPECIAL))
        assertFalse(StorageProfileService.parseZpoolAcceleratorSections(ZPOOL_WITHOUT_ACCELERATOR))
        assertFalse(StorageProfileService.parseZpoolAcceleratorSections("cannot import 'tank': no such pool"))
    }

    // ── 2. 网络/用户态 FS ─────────────────────────────────────────────────────

    @Test
    fun `network and fuse fs types yield unknown`() {
        for (fsType in listOf("nfs", "nfs4", "cifs", "smbfs", "fuse.encfs", "fuseblk")) {
            val fake = FakeSystemFiles()
            fake.mountAll(mi("/srv/av/downloads", fsType, "nas:/export"))
            fake.fsTypes["/srv/av/downloads"] = fsType

            val r = newService(fake).detect("/srv/av/downloads")

            assertEquals(StorageProfile.UNKNOWN, r.profile, "fsType=$fsType")
            assertNull(r.evidence.failureReason, "fsType=$fsType")
        }
    }

    // ── 4. 保守回退 ──────────────────────────────────────────────────────────

    @Test
    fun `missing mountinfo falls back to conservative hdd`() {
        val fake = FakeSystemFiles()
        fake.fsTypes["/srv/av/downloads"] = "ext4" // getFileStore 可用，但 /proc/self/mountinfo 缺失

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertTrue(r.evidence.failureReason!!.contains("mountinfo"))
    }

    @Test
    fun `both file store and mountinfo missing falls back to conservative hdd`() {
        val fake = FakeSystemFiles() // 全空

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertNotNull(r.evidence.failureReason)
    }

    @Test
    fun `path matching no mount entry falls back to conservative hdd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/other", "ext4", "/dev/sdb1"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("sdb", 1)

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertNotNull(r.evidence.failureReason)
    }

    @Test
    fun `rotational unreadable falls back to conservative hdd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/sdc1"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.dirs["/sys/block/sdc"] = mutableListOf() // 设备在，queue/rotational 不可读
        // 不喂 /sys/block/sdc/queue/rotational → 不可读

        val r = newService(fake).detect("/srv/av/downloads")

        assertEquals(StorageProfile.HDD, r.profile)
        assertTrue(r.evidence.failureReason!!.contains("rotational"))
    }

    // ── 最长前缀匹配 ─────────────────────────────────────────────────────────

    @Test
    fun `longest prefix mount wins`() {
        val fake = FakeSystemFiles()
        fake.mountAll(
            mi("/srv/av", "ext4", "/dev/sdb1"),               // HDD 根挂载
            mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"), // 下载目录单独 bind 到 SSD
            mi("/srv/av/downloads-old", "ext4", "/dev/sdd1"),  // 前缀相近但不同路径（边界）
        )
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.fsTypes["/srv/av"] = "ext4"
        fake.disk("nvme0n1", 0)
        fake.disk("sdb", 1)
        fake.disk("sdd", 1)

        val svc = newService(fake)
        assertEquals(StorageProfile.SSD, svc.detect("/srv/av/downloads").profile)
        assertEquals(StorageProfile.HDD, svc.detect("/srv/av/cache").profile) // 回落 /srv/av
    }

    // ── 缓存与重探 ───────────────────────────────────────────────────────────

    @Test
    fun `current caches result and redetect re-probes`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("nvme0n1", 0)
        val svc = newService(fake)

        val first = svc.detect("/srv/av/downloads")
        assertEquals(StorageProfile.SSD, first.profile)
        assertSame(first, svc.current())

        // 伪造树翻转成 ZFS → redetect 反映新形态
        fake.fsTypes["/srv/av/downloads"] = "zfs"
        fake.mountAll(mi("/srv/av/downloads", "zfs", "tank/ds"))
        val second = svc.redetect()!!
        assertEquals(StorageProfile.ZFS, second.profile)
        assertEquals(StorageProfile.ZFS, svc.current()!!.profile)
    }

    @Test
    fun `redetect without prior detect returns null`() {
        assertNull(newService(FakeSystemFiles()).redetect())
    }

    // ── storage.profile-override ─────────────────────────────────────────────

    @Test
    fun `override forces ssd over natural hdd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/sdb1"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("sdb", 1)

        val r = newService(fake, override = "ssd").detect("/srv/av/downloads")

        assertEquals(StorageProfile.SSD, r.profile)
        assertEquals(StorageProfile.SSD, r.evidence.overrideApplied)
        assertEquals(StorageProfile.HDD, r.evidence.naturalProfile) // 自然结果仍可考
    }

    @Test
    fun `override forces hdd and zfs`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("nvme0n1", 0)

        val hddForced = newService(fake, override = "hdd").detect("/srv/av/downloads")
        assertEquals(StorageProfile.HDD, hddForced.profile)
        assertEquals(StorageProfile.HDD, hddForced.evidence.overrideApplied)

        val zfsForced = newService(fake, override = "zfs").detect("/srv/av/downloads")
        assertEquals(StorageProfile.ZFS, zfsForced.profile)
        assertEquals(StorageProfile.ZFS, zfsForced.evidence.overrideApplied)
        assertNull(zfsForced.evidence.zfsCacheHint) // 强制 ZFS 无 hint → 保守
    }

    @Test
    fun `invalid override ignored and auto is natural`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av/downloads", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av/downloads"] = "ext4"
        fake.disk("nvme0n1", 0)

        for (bad in listOf("banana", "AUTO", "SSD ")) {
            assertEquals(
                StorageProfile.SSD,
                newService(fake, override = bad).detect("/srv/av/downloads").profile,
                "override='$bad'",
            )
        }
        val auto = newService(fake, override = "auto").detect("/srv/av/downloads")
        assertEquals(StorageProfile.SSD, auto.profile)
        assertNull(auto.evidence.overrideApplied)
    }

    // ── 部署不变量复查 ────────────────────────────────────────────────────────

    @Test
    fun `deploymentCheck passes when cache and db both on ssd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av"] = "ext4"
        fake.disk("nvme0n1", 0)

        val check = newService(fake).deploymentCheckFor("/srv/av/cache", "/srv/av/data/anotherviewer.db")

        assertTrue(check.invariantHeld)
        assertTrue(check.cache!!.compliant)
        assertTrue(check.db!!.compliant)
        assertEquals(StorageProfile.SSD, check.cache.profile)
        assertEquals(StorageProfile.SSD, check.db.profile)
    }

    @Test
    fun `deploymentCheck no-arg reads configured cache path and sqlite datasource url`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av"] = "ext4"
        fake.disk("nvme0n1", 0)

        val check = newService(fake).deploymentCheck()
        assertTrue(check.invariantHeld)
        assertEquals("/srv/av/cache", check.cache!!.path)
        assertEquals("/srv/av/data/anotherviewer.db", check.db!!.path)
    }

    @Test
    fun `deploymentCheck fails when db sits on zfs pool`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av", "zfs", "tank/data"))
        fake.fsTypes["/srv/av"] = "zfs"

        val check = newService(fake).deploymentCheckFor("/srv/av/cache", "/srv/av/data/anotherviewer.db")

        assertFalse(check.invariantHeld)
        assertEquals(StorageProfile.ZFS, check.cache!!.profile)
        assertEquals(StorageProfile.ZFS, check.db!!.profile)
        assertFalse(check.cache.compliant)
        assertFalse(check.db.compliant)
    }

    @Test
    fun `deploymentCheck fails when cache on ssd but db on hdd`() {
        val fake = FakeSystemFiles()
        fake.mountAll(
            mi("/srv/av", "ext4", "/dev/nvme0n1p2"),
            mi("/mnt/hdd", "ext4", "/dev/sdb1"),
        )
        fake.fsTypes["/srv/av"] = "ext4"
        fake.fsTypes["/mnt/hdd"] = "ext4"
        fake.disk("nvme0n1", 0)
        fake.disk("sdb", 1)

        val check = newService(fake).deploymentCheckFor("/srv/av/cache", "/mnt/hdd/anotherviewer.db")

        assertFalse(check.invariantHeld)
        assertTrue(check.cache!!.compliant)
        assertFalse(check.db!!.compliant)
        assertEquals(StorageProfile.HDD, check.db.profile)
    }

    @Test
    fun `deploymentCheck unknown profile counts as not held`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av", "nfs", "nas:/export"))
        fake.fsTypes["/srv/av"] = "nfs"

        val check = newService(fake).deploymentCheckFor("/srv/av/cache", "/srv/av/data/anotherviewer.db")

        assertFalse(check.invariantHeld)
        assertEquals(StorageProfile.UNKNOWN, check.cache!!.profile)
        assertEquals(StorageProfile.UNKNOWN, check.db!!.profile)
    }

    @Test
    fun `deploymentCheck with missing paths is unverifiable`() {
        val fake = FakeSystemFiles()
        fake.mountAll(mi("/srv/av", "ext4", "/dev/nvme0n1p2"))
        fake.fsTypes["/srv/av"] = "ext4"
        fake.disk("nvme0n1", 0)

        val noDb = newService(fake, datasourceUrl = "jdbc:mysql://host/db").deploymentCheckFor("/srv/av/cache", null)
        assertFalse(noDb.invariantHeld)
        assertNotNull(noDb.cache)
        assertNull(noDb.db)

        val neither = newService(fake).deploymentCheckFor(null, "")
        assertFalse(neither.invariantHeld)
        assertNull(neither.cache)
        assertNull(neither.db)
    }

    // ── zpool status 伪造输出 ─────────────────────────────────────────────────

    private companion object {
        private val ZPOOL_WITH_CACHE = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0
            	    /dev/sda ONLINE       0     0     0
            	    /dev/sdb ONLINE       0     0     0

            cache
            	  /dev/nvme0n1 -          0     0     0
        """.trimIndent()

        private val ZPOOL_WITH_SPECIAL = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0

            special
            	  mirror-1   ONLINE       0     0     0
        """.trimIndent()

        private val ZPOOL_WITHOUT_ACCELERATOR = """
            config:

            	NAME         STATE     READ WRITE CKSUM
            	tank         ONLINE       0     0     0
            	  mirror-0   ONLINE       0     0     0
        """.trimIndent()
    }
}
