package com.hippo.anotherviewer.web.service

import com.hippo.anotherviewer.web.config.SiteCoreConfigProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * P-S3 端到端：快照启动（毫秒级、零 walk）、后台全量校验修正错误快照、TTL
 * 触发的后台增量重建、损坏快照回退全量、invalidate 语义、写失败软降级。
 * 全部在临时目录进行；快照文件隔离在 `tempDir/data`。
 */
class DownloadDirIndexSnapshotTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var config: SiteCoreConfigProperties

    /** 本测试创建过的实例：tearDown 统一停掉后台线程，杜绝与 @TempDir 清理竞态。 */
    private val created = ArrayList<DownloadDirIndex>()

    @BeforeEach
    fun setUp() {
        config = SiteCoreConfigProperties()
        config.download.path = tempDir.absolutePath
        config.dataDir = File(tempDir, "data").absolutePath
    }

    @AfterEach
    fun tearDown() {
        // shutdown 等在途重建（含快照覆写）收尾并拒绝新任务——否则测试结束后
        // 后台快照写入会在 @TempDir 清理时重建 data/ 目录（DirectoryNotEmpty）。
        created.forEach { it.shutdown() }
        created.clear()
    }

    private fun gidDir(name: String): File = File(tempDir, name).apply { mkdirs() }

    private fun pushFile(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    /** interval 0 = 每请求都做指纹检查（旧行为，TTL 触发确定性最强）。 */
    private fun newIndex(intervalMs: Long = 0): DownloadDirIndex =
        DownloadDirIndex(config, intervalMs).also { created.add(it) }

    private fun store(): DirIndexSnapshotStore = DirIndexSnapshotStore(config.dataDir)

    private fun canonicalRoot(): String = tempDir.canonicalPath

    // ---- 冷启动：无快照 → 全量 walk + 快照落盘 ----

    @Test
    fun `cold start without snapshot does a full walk and writes a usable snapshot`() {
        val dir = gidDir("123-Some Title")
        pushFile(dir, "0001.jpg")
        pushFile(dir, "0002.webp")

        val index = newIndex()
        index.loadAll()

        assertEquals(1, index.walkCount, "cold start = exactly one full walk")
        assertEquals(2, index.pageCount(123L))
        assertTrue(store().file.isFile, "snapshot must be persisted to the data dir")
        assertNotNull(store().load(canonicalRoot()), "persisted snapshot must load cleanly")
    }

    // ---- 快照启动：毫秒级载入、零 walk、内容正确 ----

    @Test
    fun `snapshot start serves content with zero walk`() {
        val dir = gidDir("123-Some Title")
        pushFile(dir, "0001.jpg")
        pushFile(dir, "0002.webp")

        val first = newIndex()
        first.loadAll()
        assertEquals(1, first.walkCount)

        // 新实例从快照启动：校验 walk 关闭 → 断言零 walk。
        val second = newIndex(60_000)
        second.startVerificationAfterSnapshotLoad = false
        second.loadAll()

        assertEquals(0, second.walkCount, "snapshot load must not walk the tree")
        assertEquals(2, second.pageCount(123L))
        assertEquals("0001.jpg", second.findPage(123L, 0)!!.fileName)
        assertEquals("webp", second.findPage(123L, 1)!!.ext)
        assertEquals(dir.canonicalPath, second.dirFor(123L)!!.canonicalPath)
    }

    // ---- 后台全量校验：错误快照被修正并覆写 ----

    @Test
    fun `background verification corrects a stale snapshot and rewrites it`() {
        val dir = gidDir("5")
        pushFile(dir, "0001.jpg")
        pushFile(dir, "0002.png")
        // 手工落一份陈旧快照：gid 5 少记一页、gid 6 指向已不存在的目录。
        assertTrue(
            store().save(
                DirIndexSnapshotStore.Snapshot(
                    rootPath = canonicalRoot(),
                    savedAtMs = System.currentTimeMillis(),
                    entries = listOf(
                        DirIndexSnapshotStore.SnapshotEntry(
                            gid = 5L, dirPath = dir.path, dirMtime = dir.lastModified(),
                            pages = listOf(DirIndexSnapshotStore.SnapshotPage(1, "jpg", 3L, "0001.jpg")),
                        ),
                        DirIndexSnapshotStore.SnapshotEntry(
                            gid = 6L, dirPath = File(tempDir, "6-gone").path, dirMtime = 1L,
                            pages = listOf(DirIndexSnapshotStore.SnapshotPage(1, "jpg", 3L, "0001.jpg")),
                        ),
                    ),
                )
            )
        )

        // 关闭自动校验调度：载入后陈旧内容确定性地在途（校验由 interval 0 的
        // 指纹检查触发，避免后台线程抢在断言前跑完）。
        val index = newIndex()
        index.startVerificationAfterSnapshotLoad = false
        index.loadAll()
        assertEquals(0, index.walkCount, "stale snapshot still loads with zero walk")
        assertEquals(1, index.pageCount(5L), "stale content is served until verification lands")
        assertEquals(0, index.dirScanCount, "serving from snapshot scans nothing")

        // 后台全量校验（不信任快照 mtime，逐目录重扫）→ 修正 + 覆写快照。
        index.awaitBackgroundIdleForTest()
        assertEquals(1, index.walkCount, "verification is the only walk")
        assertEquals(1, index.dirScanCount, "verification is a FULL rescan (snapshot mtimes are not trusted)")
        assertEquals(2, index.pageCount(5L), "missing page found by the verification walk")
        assertEquals(0, index.pageCount(6L), "entry for a deleted directory is dropped")

        // 覆写后的快照：下一个零 walk 启动直接读到修正后的内容。
        val third = newIndex(60_000)
        third.startVerificationAfterSnapshotLoad = false
        third.loadAll()
        assertEquals(0, third.walkCount)
        assertEquals(2, third.pageCount(5L))
        assertNull(third.findPage(6L, 0))
    }

    // ---- 损坏快照：回退全量 walk，快照被重写为可用 ----

    @Test
    fun `corrupt snapshot falls back to a full walk and is rewritten`() {
        val dir = gidDir("77")
        pushFile(dir, "0001.jpg")
        assertTrue(store().save(DirIndexSnapshotStore.Snapshot(canonicalRoot(), 1L, emptyList())))
        // 乱字节覆盖快照（模拟半写/位损坏）。
        store().file.writeBytes("garbage-over-snapshot".toByteArray())

        val index = newIndex()
        index.loadAll()

        assertEquals(1, index.walkCount, "corrupt snapshot → synchronous full walk (original behavior)")
        assertEquals(1, index.pageCount(77L))
        assertEquals("0001.jpg", index.findPage(77L, 0)!!.fileName)
        assertNotNull(store().load(canonicalRoot()), "snapshot is usable again after the fallback walk")
    }

    // ---- TTL 触发的后台增量重建：只重扫变化目录 ----

    @Test
    fun `background rebuild after a ttl fingerprint change rescans only changed directories`() {
        gidDir("2").let { pushFile(it, "0001.jpg") }
        val dir3 = gidDir("3").let { pushFile(it, "0001.jpg"); it }
        gidDir("4").let { pushFile(it, "0001.jpg") }

        val index = newIndex()
        index.loadAll()
        assertEquals(1, index.walkCount)
        assertEquals(3, index.dirScanCount, "full walk scans every directory once")

        // 变更：dir3 新增一页（mtime 变化）+ 新目录 8（指纹变化）。
        pushFile(dir3, "0002.png")
        dir3.setLastModified(dir3.lastModified() + 10_000)
        gidDir("8").let { pushFile(it, "0001.gif") }

        // interval 0：任意查询的指纹检查发现变化 → 调度后台重建；dir4 索引
        // 命中（零重扫）照常服务。
        assertEquals(1, index.pageCount(4L))

        index.awaitBackgroundIdleForTest()

        assertEquals(2, index.walkCount)
        assertEquals(5, index.dirScanCount, "incremental: only dir3 (changed) and dir8 (new) rescanned; 2 and 4 reused")
        assertEquals(1, index.pageCount(2L))
        assertEquals(2, index.pageCount(3L), "changed directory rescanned")
        assertEquals(1, index.pageCount(8L), "new directory picked up")
    }

    @Test
    fun `deleted directory is dropped by the incremental rebuild without any rescan`() {
        gidDir("2").let { pushFile(it, "0001.jpg") }
        val dir3 = gidDir("3").let { pushFile(it, "0001.jpg"); it }

        val index = newIndex()
        index.loadAll()
        assertEquals(2, index.dirScanCount)

        dir3.deleteRecursively()

        assertEquals(1, index.pageCount(2L), "triggers the fingerprint check → background rebuild")
        index.awaitBackgroundIdleForTest()

        assertEquals(2, index.walkCount)
        assertEquals(2, index.dirScanCount, "deletion is sensed from the root listing — zero per-dir scans")
        assertEquals(0, index.pageCount(3L), "removed directory no longer indexed")
        assertEquals(1, index.pageCount(2L))
    }

    // ---- 快照载入后的 invalidate：立即反映磁盘（语义不变） ----

    @Test
    fun `invalidate after snapshot load immediately reflects disk changes`() {
        val dir = gidDir("9")
        pushFile(dir, "0001.jpg")
        pushFile(dir, "0002.png")
        newIndex().loadAll()

        val index = newIndex(60_000)
        index.startVerificationAfterSnapshotLoad = false
        index.loadAll()
        assertEquals(2, index.pageCount(9L))

        File(dir, "0002.png").delete()
        dir.setLastModified(dir.lastModified() + 10_000)
        index.invalidate(9L)

        assertEquals(1, index.pageCount(9L))
        assertNull(index.findPage(9L, 1))
        assertEquals("0001.jpg", index.findPage(9L, 0)!!.fileName)
    }

    // ---- 写失败软降级：不抛异常、无 temp 残留、索引照常工作 ----

    @Test
    fun `snapshot write failure keeps the index working and leaves no temp residue`() {
        val dataDir = File(tempDir, "data")
        dataDir.mkdirs()
        assertTrue(dataDir.setWritable(false), "arrange: unwritable data dir (assumes non-root test process)")
        try {
            gidDir("123").let { pushFile(it, "0001.jpg") }
            val index = newIndex()
            index.loadAll()

            assertEquals(1, index.walkCount)
            assertEquals(1, index.pageCount(123L), "index works without its snapshot")
            assertTrue(
                dataDir.listFiles()!!.none { it.name.endsWith(".tmp") || it.name == DirIndexSnapshotStore.SNAPSHOT_FILE_NAME },
                "failed save leaves neither the snapshot nor temp residue",
            )
        } finally {
            assertTrue(dataDir.setWritable(true), "cleanup: restore data dir")
        }
    }

    // ---- 重建在途 → 完成后新索引可见，且被写进覆写后的快照 ----

    @Test
    fun `externally added directory lands in the index after the background rebuild and in the rewritten snapshot`() {
        gidDir("4").let { pushFile(it, "0001.jpg") }
        val first = newIndex()
        first.loadAll()
        assertEquals(1, first.walkCount)
        assertEquals(1, first.pageCount(4L))

        // 外部复制进来的新缓存目录（TTL 指纹变化 → 只调度后台重建）。
        gidDir("12-Cool").let { pushFile(it, "0001.jpg") }

        // interval 0：对既有 gid 的查询发现指纹变化并触发调度，索引命中照常。
        val index = newIndex()
        index.startVerificationAfterSnapshotLoad = false
        index.loadAll() // 快照启动：零 walk
        assertEquals(0, index.walkCount)
        assertEquals(1, index.pageCount(4L), "triggers the fingerprint check → background rebuild scheduled")

        index.awaitBackgroundIdleForTest()

        assertEquals(1, index.walkCount, "only the background rebuild walked")
        assertEquals(1, index.pageCount(12L), "new directory visible after the rebuild")

        // 覆写后的快照让下一次启动零 walk 直接看到新目录——证明新目录来自
        // 后台重建（而非查询路径的按需自愈）。
        val third = newIndex(60_000)
        third.startVerificationAfterSnapshotLoad = false
        third.loadAll()
        assertEquals(0, third.walkCount)
        assertEquals(1, third.pageCount(12L))
        assertEquals(1, third.pageCount(4L))
    }
}
