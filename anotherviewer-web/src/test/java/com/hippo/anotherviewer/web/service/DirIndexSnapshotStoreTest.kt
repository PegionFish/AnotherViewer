package com.hippo.anotherviewer.web.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Pins [DirIndexSnapshotStore] (P-S3)：紧凑二进制帧 `[magic][version][crc32]
 * [len][payload]` 的完整往返，以及一切损坏形态（缺文件、乱字节、截断、位翻转、
 * 版本/魔数不符、下载根不符）都必须拒绝载入（null → 调用方回退全量 walk），
 * 写入原子替换且无 `.tmp` 残留、写失败不抛出。
 */
class DirIndexSnapshotStoreTest {

    @TempDir
    lateinit var tempDir: File

    private val dataDir: File get() = File(tempDir, "data")

    private fun store(): DirIndexSnapshotStore = DirIndexSnapshotStore(dataDir.absolutePath)

    private fun sample() = DirIndexSnapshotStore.Snapshot(
        rootPath = "/downloads",
        savedAtMs = 42L,
        entries = listOf(
            DirIndexSnapshotStore.SnapshotEntry(
                gid = 123L,
                dirPath = "/downloads/123-Some Title",
                dirMtime = 111L,
                pages = listOf(
                    DirIndexSnapshotStore.SnapshotPage(1, "jpg", 3L, "0001.jpg"),
                    DirIndexSnapshotStore.SnapshotPage(2, "webp", 3L, "0002.webp"),
                ),
            ),
            DirIndexSnapshotStore.SnapshotEntry(
                gid = 9L,
                dirPath = "/downloads/9",
                dirMtime = 222L,
                pages = emptyList(),
            ),
        ),
    )

    @Test
    fun `save then load round trips entries exactly`() {
        val snapshot = sample()
        assertTrue(store().save(snapshot), "arrange: first save succeeds")

        val loaded = store().load("/downloads")
        assertEquals(snapshot, loaded, "round trip must preserve root, savedAt and every entry")
    }

    @Test
    fun `load returns null when the snapshot file is absent`() {
        assertNull(store().load("/downloads"))
    }

    @Test
    fun `load returns null when the root path does not match (foreign machine or download root)`() {
        assertTrue(store().save(sample()))
        assertNull(store().load("/another/root"), "snapshot from another download root must be ignored")
    }

    @Test
    fun `load returns null on garbage bytes`() {
        dataDir.mkdirs()
        Files.write(store().file.toPath(), "this is not a snapshot".toByteArray())
        assertNull(store().load("/downloads"))
    }

    @Test
    fun `load returns null on a truncated frame`() {
        assertTrue(store().save(sample()))
        val bytes = Files.readAllBytes(store().file.toPath())
        Files.write(store().file.toPath(), bytes.copyOfRange(0, bytes.size / 2))
        assertNull(store().load("/downloads"), "truncated frame = damaged = refuse to load")
    }

    @Test
    fun `load returns null when a payload byte is flipped (crc mismatch)`() {
        assertTrue(store().save(sample()))
        val bytes = Files.readAllBytes(store().file.toPath())
        // 翻转 payload 区中部一个字节（头部为 magic+version+crc+len = 20B）。
        val mid = bytes.size / 2
        assertTrue(mid > 20, "arrange: payload region exists")
        bytes[mid] = (bytes[mid].toInt() xor 0x55).toByte()
        Files.write(store().file.toPath(), bytes)
        assertNull(store().load("/downloads"), "crc must catch silent bit rot (HDD 迁移场景)")
    }

    @Test
    fun `load returns null on a wrong format version`() {
        dataDir.mkdirs()
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use {
            it.writeInt(DirIndexSnapshotStore.MAGIC)
            it.writeInt(DirIndexSnapshotStore.VERSION + 1)
            it.writeLong(0L)
            it.writeInt(0)
        }
        Files.write(store().file.toPath(), frame.toByteArray())
        assertNull(store().load("/downloads"))
    }

    @Test
    fun `load returns null on a wrong magic`() {
        dataDir.mkdirs()
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use {
            it.writeInt(0x12345678)
            it.writeInt(DirIndexSnapshotStore.VERSION)
            it.writeLong(0L)
            it.writeInt(0)
        }
        Files.write(store().file.toPath(), frame.toByteArray())
        assertNull(store().load("/downloads"))
    }

    @Test
    fun `save atomically replaces and leaves no temp residue`() {
        assertTrue(store().save(sample()))
        val changed = sample().copy(
            entries = listOf(
                DirIndexSnapshotStore.SnapshotEntry(7L, "/downloads/7", 1L, emptyList())
            )
        )
        assertNotEquals(sample(), changed)
        assertTrue(store().save(changed))

        val loaded = store().load("/downloads")
        assertEquals(changed, loaded, "second save must fully replace the first snapshot")
        assertEquals(
            listOf(DirIndexSnapshotStore.SNAPSHOT_FILE_NAME),
            dataDir.listFiles()!!.map { it.name }.sorted(),
            "no .tmp residue beside the snapshot",
        )
    }

    @Test
    fun `save creates a missing data directory and an empty snapshot round trips`() {
        assertFalse(dataDir.exists(), "arrange: data dir absent")
        val empty = DirIndexSnapshotStore.Snapshot("/downloads", 1L, emptyList())
        assertTrue(store().save(empty), "missing data dir is created by save")
        assertEquals(empty, store().load("/downloads"))
    }

    @Test
    fun `save into an unwritable data dir fails softly without throwing`() {
        dataDir.mkdirs()
        assertTrue(dataDir.setWritable(false), "arrange: unwritable data dir (assumes non-root test process)")
        try {
            val broken = store()
            // 只读目录：无法创建 temp 文件——save 必须吞掉异常返回 false。
            assertFalse(broken.save(sample()))
            assertTrue(
                dataDir.listFiles()!!.none { it.name.endsWith(".tmp") },
                "failed write must not leave temp residue",
            )
        } finally {
            assertTrue(dataDir.setWritable(true), "cleanup: restore data dir")
        }
    }
}
