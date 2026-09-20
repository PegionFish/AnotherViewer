package com.hippo.anotherviewer.web.service

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

/**
 * P-S3（Wave 1）：[DownloadDirIndex] 的快照持久化。
 *
 * 把索引内容（gid → 目录路径 + 目录 mtime + 页文件清单）序列化到 data 目录
 * 的单个文件，启动时毫秒级载入替代 20-30s 的全量 walk（HDD 迁移硬前置）。
 *
 * 格式（紧凑二进制，自定）：`[magic][version][crc32][payloadLen][payload]`，
 * payload 为 DataOutputStream 编码的 root 路径、保存时间与条目列表；crc32
 * 覆盖整个 payload。载入时依次校验 magic、版本、长度上限、CRC 与 root 路径
 * 匹配——任何一项不符（含文件缺失/损坏/来自另一台机器或另一个下载根）一律
 * 返回 null，由调用方回退全量 walk，绝不带病载入。
 *
 * 写入采用 temp+rename 原子替换（与 DownloadService 的页文件落盘同款）：
 * 崩溃/断电最多留下一个孤儿 `.tmp`，目标文件要么是完整的旧快照要么是完整
 * 的新快照。写失败只告警不抛出——快照是纯优化，索引本体永远可用。
 */
class DirIndexSnapshotStore(dataDirPath: String) {

    private val logger = LoggerFactory.getLogger(DirIndexSnapshotStore::class.java)

    private val dir = File(dataDirPath)

    /** 快照文件：`<dataDir>/dir-index.snapshot`（data/ 已 gitignore）。 */
    val file: File get() = File(dir, SNAPSHOT_FILE_NAME)

    /** One indexed page in a snapshot entry; fields mirror [PageRef]. */
    data class SnapshotPage(val page: Int, val ext: String, val size: Long, val fileName: String)

    /** One gid directory in a snapshot: absolute dir path + scan-time mtime + pages. */
    data class SnapshotEntry(val gid: Long, val dirPath: String, val dirMtime: Long, val pages: List<SnapshotPage>)

    /** Whole snapshot payload: downloads root it was taken under + entries. */
    data class Snapshot(val rootPath: String, val savedAtMs: Long, val entries: List<SnapshotEntry>)

    /**
     * Load and fully validate the snapshot. Returns null (with a logged
     * reason for anything other than "simply absent") when the file is
     * missing, corrupt, of another format version, CRC-mismatched, or taken
     * under a different downloads root ([expectedRootPath]).
     */
    fun load(expectedRootPath: String): Snapshot? {
        val f = file
        if (!f.isFile) return null
        val snapshot = try {
            FileInputStream(f).use { ins -> DataInputStream(ins).use { readFrame(it) } }
        } catch (e: Exception) {
            logger.warn(
                "DirIndexSnapshotStore: ignoring unusable snapshot {} ({})", f.absolutePath, e.toString()
            )
            null
        } ?: return null
        if (snapshot.rootPath != expectedRootPath) {
            logger.info(
                "DirIndexSnapshotStore: snapshot root {} does not match current {} — ignoring",
                snapshot.rootPath, expectedRootPath
            )
            return null
        }
        return snapshot
    }

    /**
     * Atomically replace the snapshot file. Returns false on any failure
     * (never throws): the index keeps running from memory and the next
     * rebuild simply writes it again.
     */
    fun save(snapshot: Snapshot): Boolean {
        val target = file
        try {
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                logger.warn("DirIndexSnapshotStore: cannot create data dir {} — snapshot skipped", parent.absolutePath)
                return false
            }
            val payload = ByteArrayOutputStream(64 * 1024)
            DataOutputStream(payload).use { writeSnapshot(it, snapshot) }
            val bytes = payload.toByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            val frame = ByteArrayOutputStream(bytes.size + 24)
            DataOutputStream(frame).use {
                it.writeInt(MAGIC)
                it.writeInt(VERSION)
                it.writeLong(crc)
                it.writeInt(bytes.size)
                it.write(bytes)
            }
            // temp+rename 原子替换（一期 P1-1 同款）；temp 失败即清理，不留残骸。
            val temp = File(target.parentFile, "." + target.name + "." + UUID.randomUUID() + ".tmp")
            try {
                Files.write(temp.toPath(), frame.toByteArray())
                try {
                    Files.move(
                        temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
            return true
        } catch (e: Exception) {
            logger.warn("DirIndexSnapshotStore: snapshot save failed (index keeps working): {}", e.toString())
            return false
        }
    }

    private fun writeSnapshot(out: DataOutputStream, snapshot: Snapshot) {
        out.writeUTF(snapshot.rootPath)
        out.writeLong(snapshot.savedAtMs)
        out.writeInt(snapshot.entries.size)
        for (entry in snapshot.entries) {
            out.writeLong(entry.gid)
            out.writeUTF(entry.dirPath)
            out.writeLong(entry.dirMtime)
            out.writeInt(entry.pages.size)
            for (page in entry.pages) {
                out.writeInt(page.page)
                out.writeUTF(page.ext)
                out.writeLong(page.size)
                out.writeUTF(page.fileName)
            }
        }
    }

    /** Read the framed payload; throws on any structural damage (caller catches → null). */
    private fun readFrame(d: DataInputStream): Snapshot? {
        val magic = d.readInt()
        if (magic != MAGIC) return null
        val version = d.readInt()
        if (version != VERSION) return null
        val crc = d.readLong()
        val length = d.readInt()
        if (length < 0 || length > MAX_PAYLOAD_BYTES) return null
        val payload = ByteArray(length)
        d.readFully(payload)
        if (CRC32().apply { update(payload) }.value != crc) return null
        val input = DataInputStream(ByteArrayInputStream(payload))
        val rootPath = input.readUTF()
        val savedAtMs = input.readLong()
        val entryCount = input.readInt()
        if (entryCount < 0) return null
        val entries = ArrayList<DirIndexSnapshotStore.SnapshotEntry>(entryCount)
        for (i in 0 until entryCount) {
            val gid = input.readLong()
            val dirPath = input.readUTF()
            val dirMtime = input.readLong()
            val pageCount = input.readInt()
            if (pageCount < 0) return null
            val pages = ArrayList<DirIndexSnapshotStore.SnapshotPage>(pageCount)
            for (j in 0 until pageCount) {
                pages.add(
                    DirIndexSnapshotStore.SnapshotPage(
                        page = input.readInt(),
                        ext = input.readUTF(),
                        size = input.readLong(),
                        fileName = input.readUTF(),
                    )
                )
            }
            entries.add(DirIndexSnapshotStore.SnapshotEntry(gid, dirPath, dirMtime, pages))
        }
        return Snapshot(rootPath, savedAtMs, entries)
    }

    companion object {
        const val SNAPSHOT_FILE_NAME = "dir-index.snapshot"

        /** Bump on any payload layout change; older snapshots are then ignored. */
        internal const val VERSION = 1

        /** Frame magic ("AVDI" as ASCII). */
        internal const val MAGIC = 0x41564449.toInt()

        /** Hard cap for the payload length field: refuse absurd/corrupt frames. */
        internal const val MAX_PAYLOAD_BYTES = 1 shl 30
    }
}
