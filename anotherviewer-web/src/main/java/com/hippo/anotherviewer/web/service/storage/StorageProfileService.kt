package com.hippo.anotherviewer.web.service.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * 存储池形态（设计：docs/design-2026-09-20-storage-profile-autoadapt.md）。
 *
 * 部署不变量：应用/Cache/DB 恒在 SSD，只有下载目录不确定——下载目录在哪类池上，
 * 池级行为就适配哪类（SSD | HDD | ZFS | UNKNOWN）。
 */
enum class StorageProfile {
    /** 纯 SSD：全部底层盘 rotational=0。 */
    SSD,

    /**
     * 机械盘或含机械盘的混合池（短板决定）。同时是一切探测失败时的保守回退：
     * 在 HDD 上跑 SSD 行为有害，反向无害。
     */
    HDD,

    /**
     * ZFS 池——自成 profile，池内盘不看 rotational。
     * 是否带 cache/special vdev 见 [StorageEvidence.zfsCacheHint]（尽力而为）。
     */
    ZFS,

    /** 网络/用户态 FS（nfs/cifs/smbfs/fuse.* 等）——形态不可判，按保守参数跑。 */
    UNKNOWN,
}

/** 一次探测的完整证据：INFO 日志 + 管理页展示（Wave 3）+ 测试断言共用。 */
data class StorageEvidence(
    val rootPath: String,
    /** FS 类型：Files.getFileStore().type() 优先，回落 mountinfo 的 fs 类型。 */
    val fsType: String?,
    /** fsType 来源："file-store" | "mountinfo" | null（两者都拿不到）。 */
    val fsTypeSource: String?,
    /** mountinfo 最长前缀匹配到的挂载源（如 /dev/sdf1、/dev/mapper/vg0-lv0、tank/downloads）。 */
    val mountSource: String?,
    /** 沿 /sys/block/<dev>/slaves 递归展开后的底层物理盘。 */
    val leafDevices: List<String>,
    /** 底层盘 → /sys/block/<dev>/queue/rotational 值（null=不可读）。 */
    val rotational: Map<String, Int?>,
    /** ZFS 池名（mountinfo 源字段 pool/dataset 的 pool 段）。 */
    val zfsPool: String?,
    /**
     * ZFS cache（L2ARC）/ special vdev 提示：true=有（读并发 16），false=确认无（并发 8），
     * null=探测失败（保守 ZFS，并发 8）。
     */
    val zfsCacheHint: Boolean?,
    /** 非 null：探测未走通/未走全的原因（此时结果为保守 HDD 或保守 ZFS）。 */
    val failureReason: String?,
    /** storage.profile-override 强制时记录被强制的 profile。 */
    val overrideApplied: StorageProfile?,
    /** override 生效时记录未强制的自然探测形态（无 override 时为 null）。 */
    val naturalProfile: StorageProfile?,
)

/** 探测结果：形态 + 证据。 */
data class StorageProfileResult(val profile: StorageProfile, val evidence: StorageEvidence)

/** 部署不变量复查结果（cache 目录与 DB 文件所在卷必须为 SSD）。 */
data class DeploymentCheck(
    /** true 仅当两腿均可判定且 profile 均为 SSD；任一腿缺失（路径未配置/无法解析）或非 SSD 即 false。 */
    val invariantHeld: Boolean,
    /** cache 目录一腿；null = 路径未配置，无法验证。 */
    val cache: PathCheck?,
    /** DB 文件一腿；null = 路径未配置/数据源 URL 无法解析，无法验证。 */
    val db: PathCheck?,
)

/** 部署不变量单腿检查。 */
data class PathCheck(
    val path: String,
    val profile: StorageProfile,
    val evidence: StorageEvidence,
    /** 仅 SSD 满足部署不变量；HDD/ZFS=部署错误，UNKNOWN=无法验证（同样告警，Wave 3 凭 evidence 区分展示）。 */
    val compliant: Boolean,
)

/**
 * 可注入的系统观测面。主代码只经由它拿 mountinfo/sys 数据、定位与执行 zpool——
 * 单测注入伪造文本树（严禁读真实系统，macOS 上 /proc、/sys 天然缺失）。
 */
interface SystemFiles {
    /** 读取文本文件内容；不存在/不可读返回 null。 */
    fun readFile(path: String): String?

    /** 列目录条目（已排序）；不存在/不可读返回 null。 */
    fun listDir(path: String): List<String>?

    /** 在 PATH 与常见 sbin 目录中定位可执行文件；找不到返回 null。 */
    fun findExecutable(name: String): String?

    /** 执行外部命令；超时/非零退出/失败返回 null（stdout，已合并 stderr）。 */
    fun exec(command: List<String>, timeoutMs: Long): String?

    /** JVM Files.getFileStore(path).type()；失败（路径不存在等）返回 null。 */
    fun fileStoreType(path: String): String?
}

/** 生产实现：真实文件系统观测。macOS 上 /proc、/sys 不存在 → 探测自然回落保守 HDD。 */
open class DefaultSystemFiles : SystemFiles {
    override fun readFile(path: String): String? = runCatching {
        val f = File(path)
        if (f.exists() && f.isFile) f.readText() else null
    }.getOrNull()

    override fun listDir(path: String): List<String>? = runCatching {
        val f = File(path)
        if (f.isDirectory) f.list()?.sorted() else null
    }.getOrNull()

    override fun findExecutable(name: String): String? {
        val dirs = (System.getenv("PATH")?.split(File.pathSeparator).orEmpty()) + COMMON_SBIN_DIRS
        return dirs.firstNotNullOfOrNull { dir ->
            File(dir, name).takeIf { it.isFile && it.canExecute() }?.absolutePath
        }
    }

    /**
     * zpool status 输出量级为 KB，先等退出再读管道即可；输出超管道容量会导致
     * 进程写阻塞→超时→按失败处理（destroyForcibly 自灭），符合「超时自灭」要求。
     */
    override fun exec(command: List<String>, timeoutMs: Long): String? = runCatching {
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            val done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done || proc.exitValue() != 0) null
            else proc.inputStream.readBytes().toString(Charsets.UTF_8)
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }.getOrNull()

    override fun fileStoreType(path: String): String? = runCatching {
        Files.getFileStore(File(path).toPath()).type()
    }.getOrNull()

    private companion object {
        /** 应用进程 PATH 常不含 sbin，zpool 通常在 /usr/sbin。 */
        val COMMON_SBIN_DIRS = listOf("/usr/local/sbin", "/usr/sbin", "/sbin", "/usr/local/bin", "/usr/bin", "/bin")
    }
}

/**
 * 存储池形态探测（P1）。算法（与设计文档 §一 一致）：
 *
 * 1. `Files.getFileStore(rootPath).type()`（回落 mountinfo 的 fs 类型）== "zfs" → ZFS profile；
 *    尽力从 mountinfo 源字段取 pool 名，exec `zpool status -p <pool>` 解析 cache/special vdev
 *    得 cacheHint（命令缺失/失败/超时 → 无 hint 保守 ZFS）。
 * 2. fs ∈ {nfs, cifs, smbfs, fuse.*} → UNKNOWN。
 * 3. 否则解析块设备回转标志：mountinfo 最长前缀匹配 → 挂载源设备 → 分区溯父（sdf1→sdf）
 *    → dm/LVM/md（含 LUKS）沿 /sys/block/<dev>/slaves 递归展开（btrfs 另沿 /sys/fs/btrfs/<uuid>/devices）
 *    → 读每个底层盘 /sys/block/<dev>/queue/rotational。任一=1 → HDD；全=0 → SSD；混合 → HDD（短板决定）。
 * 4. 一切失败（解析不出/权限不足/文件不存在/rotational 不可读）→ HDD（保守）。
 *
 * 结果 + 证据记一条 INFO 并缓存于本单例（[current] / [redetect]）；
 * `storage.profile-override`（auto|ssd|hdd|zfs，默认 auto）可强制指定。
 */
@Service
class StorageProfileService(
    /** 系统观测面（单测注入伪造文本树；生产用 [DefaultSystemFiles]）。 */
    private val systemFiles: SystemFiles = DefaultSystemFiles(),
    /** storage.profile-override：auto | ssd | hdd | zfs（非法值按 auto 处理并 warn）。 */
    @Value("\${storage.profile-override:auto}") private val profileOverride: String = "auto",
    /** 部署不变量复查用：cache 目录（anotherviewer.download.cache-path）。 */
    @Value("\${anotherviewer.download.cache-path:}") private val configuredCachePath: String = "",
    /** 部署不变量复查用：从 spring.datasource.url 解析 SQLite DB 文件路径。 */
    @Value("\${spring.datasource.url:}") private val configuredDatasourceUrl: String = "",
) {

    @Volatile private var cachedRoot: String? = null

    @Volatile private var cachedResult: StorageProfileResult? = null

    /** 最近一次 [detect] 的结果（内存单例缓存；未经 detect 前为 null）。 */
    fun current(): StorageProfileResult? = cachedResult

    /**
     * 对 rootPath 做一次探测并缓存为当前结果。
     * 探测时机由接线方驱动：启动、download.path 配置变更、管理端点手动重探（[redetect]）。
     */
    @Synchronized
    fun detect(rootPath: String): StorageProfileResult {
        if (rootPath.isBlank()) {
            return result(rootPath, StorageProfile.HDD, failureReason = "empty root path")
                .also { cachedRoot = rootPath; cachedResult = it }
        }
        val probed = probe(rootPath)
        cachedRoot = rootPath
        cachedResult = probed
        return probed
    }

    /** 对最近一次 detect 的 root 重探（管理端点 POST /api/v1/admin/storage/redetect 用）。 */
    @Synchronized
    fun redetect(): StorageProfileResult? = cachedRoot?.let { detect(it) }

    /** 部署不变量复查：对配置的 cache 目录与 DB 文件路径跑同一探测（结果不占用下载目录的单例缓存）。 */
    fun deploymentCheck(): DeploymentCheck =
        deploymentCheckFor(configuredCachePath, sqlitePathFromDatasourceUrl(configuredDatasourceUrl))

    /**
     * 部署不变量复查（应用/Cache/DB 恒在 SSD，只有下载目录不确定）：
     * 对 cache 目录与 DB 文件各跑一次 [probe]。invariantHeld = 两腿均可判定且 profile 均为 SSD。
     * UNKNOWN（网络 FS）同样视为未通过——无法验证即告警，Wave 3 可凭 evidence 区分展示。
     */
    fun deploymentCheckFor(cacheDir: String?, dbFile: String?): DeploymentCheck {
        val cacheCheck = cacheDir?.takeIf { it.isNotBlank() }?.let(::checkPath)
        val dbCheck = dbFile?.takeIf { it.isNotBlank() }?.let(::checkPath)
        val held = cacheCheck?.compliant == true && dbCheck?.compliant == true
        val check = DeploymentCheck(held, cacheCheck, dbCheck)
        log.info("deployment invariant check: held={} cache={} db={}", held, cacheCheck, dbCheck)
        return check
    }

    // ── 内部：单次探测 ─────────────────────────────────────────────────────────

    private fun checkPath(path: String): PathCheck {
        val probed = probe(path)
        return PathCheck(path, probed.profile, probed.evidence, compliant = probed.profile == StorageProfile.SSD)
    }

    private fun probe(rootPath: String): StorageProfileResult {
        val natural = probeNatural(rootPath)
        val finalResult = parsedOverride()?.let { forced ->
            StorageProfileResult(forced, natural.evidence.copy(overrideApplied = forced, naturalProfile = natural.profile))
        } ?: natural
        val e = finalResult.evidence
        log.info(
            "storage profile: root={} profile={} fs={} (via {}) mountSource={} leaves={} rotational={} " +
                "zfsPool={} zfsCacheHint={} override={} failure={}",
            e.rootPath, finalResult.profile, e.fsType, e.fsTypeSource, e.mountSource, e.leafDevices,
            e.rotational, e.zfsPool, e.zfsCacheHint, e.overrideApplied, e.failureReason,
        )
        return finalResult
    }

    private fun probeNatural(rootPath: String): StorageProfileResult {
        val fsFromStore = systemFiles.fileStoreType(rootPath)
        val mount = mountFor(rootPath)
        val fsType = fsFromStore ?: mount?.fsType
        val fsTypeSource = when {
            fsFromStore != null -> FS_SOURCE_FILE_STORE
            mount != null -> FS_SOURCE_MOUNTINFO
            else -> null
        }
        return when {
            // 1. ZFS
            fsType == "zfs" -> zfsResult(rootPath, fsType, fsTypeSource, mount)
            // 2. 网络/用户态 FS
            fsType != null && (fsType in NETWORK_FS_TYPES || fsType.startsWith("fuse")) ->
                result(rootPath, StorageProfile.UNKNOWN, fsType, fsTypeSource, mount?.source)
            // 3/4. 块设备回转分析（失败 → 保守 HDD）
            else -> blockDeviceResult(rootPath, fsType, fsTypeSource, mount)
        }
    }

    private fun zfsResult(
        rootPath: String,
        fsType: String?,
        fsTypeSource: String?,
        mount: MountEntry?,
    ): StorageProfileResult {
        val pool = mount?.source?.substringBefore('/')?.takeIf { it.isNotBlank() }
        val hint = pool?.let(::probeZpoolCacheHint)
        return result(
            rootPath, StorageProfile.ZFS, fsType, fsTypeSource, mount?.source,
            zfsPool = pool, zfsCacheHint = hint,
            failureReason = if (hint == null) "zpool status unavailable — conservative ZFS" else null,
        )
    }

    /** 有 zpool 命令且 `zpool status -p <pool>` 5s 内成功才解析；否则 null（保守 ZFS）。 */
    private fun probeZpoolCacheHint(pool: String): Boolean? {
        val zpool = systemFiles.findExecutable("zpool") ?: return null
        val output = systemFiles.exec(listOf(zpool, "status", "-p", pool), ZPOOL_TIMEOUT_MS) ?: return null
        return parseZpoolAcceleratorSections(output)
    }

    private fun blockDeviceResult(
        rootPath: String,
        fsType: String?,
        fsTypeSource: String?,
        mount: MountEntry?,
    ): StorageProfileResult {
        if (mount == null) {
            return result(
                rootPath, StorageProfile.HDD, fsType, fsTypeSource, null,
                failureReason = "mountinfo unavailable: no mount entry matches path",
            )
        }
        val source = mount.source?.takeIf { it.isNotBlank() }
            ?: return result(rootPath, StorageProfile.HDD, fsType, fsTypeSource, null, failureReason = "mount source missing")
        // /sys/block 顶层列表只用于 dm 设备名反查（/dev/mapper/xxx → dm-N）；
        // 单个设备的存在性按各自 sysfs 目录判断（listDir 非空返回即存在）。
        val dmCandidates = systemFiles.listDir(SYS_BLOCK).orEmpty()
        val leaves = linkedSetOf<String>()
        collectLeafDisks(topLevelDeviceName(source), dmCandidates, linkedSetOf(), leaves)
        if (fsType == "btrfs") collectBtrfsMembers(dmCandidates, leaves)
        if (leaves.isEmpty()) {
            return result(
                rootPath, StorageProfile.HDD, fsType, fsTypeSource, source,
                failureReason = "no leaf block device resolved from source '$source'",
            )
        }
        val rotational = leaves.associateWith {
            systemFiles.readFile("$SYS_BLOCK/$it/queue/rotational")?.trim()?.toIntOrNull()
        }
        val profile = when {
            rotational.values.any { it == 1 } -> StorageProfile.HDD          // 任一机械盘 / 混合短板
            rotational.values.all { it == 0 } -> StorageProfile.SSD          // 全部可读且全 0
            else -> StorageProfile.HDD                                       // 部分不可读 → 保守
        }
        val failure = if (profile == StorageProfile.SSD || rotational.values.any { it == 1 }) null
        else "rotational unreadable for ${rotational.filterValues { it == null }.keys}"
        return result(rootPath, profile, fsType, fsTypeSource, source, leaves.toList(), rotational, failureReason = failure)
    }

    /** 沿 slaves 递归展开到无 slaves 的底层盘；分区先溯父；访问集防环。 */
    private fun collectLeafDisks(device: String, dmCandidates: List<String>, visited: MutableSet<String>, leaves: MutableSet<String>) {
        if (device.isBlank() || !visited.add(device)) return
        val disk = canonicalDiskName(device, dmCandidates) ?: return
        val slaves = systemFiles.listDir("$SYS_BLOCK/$disk/slaves")
            ?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (slaves.isNullOrEmpty()) leaves.add(disk)
        else slaves.forEach { collectLeafDisks(it, dmCandidates, visited, leaves) }
    }

    /**
     * btrfs 多设备：btrfs 不建 dm 设备、无 slaves，成员沿 /sys/fs/btrfs/<uuid>/devices 取。
     * 目录不存在（非 Linux/无权限）则静默忽略——只少看成员，源设备分析仍在。
     */
    private fun collectBtrfsMembers(dmCandidates: List<String>, leaves: MutableSet<String>) {
        val uuids = systemFiles.listDir(SYS_FS_BTRFS) ?: return
        uuids.forEach { uuid ->
            systemFiles.listDir("$SYS_FS_BTRFS/$uuid/devices")
                ?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { collectLeafDisks(it, dmCandidates, linkedSetOf(), leaves) }
        }
    }

    /** 设备名 → /sys/block 下的顶层盘名：直盘原样；/dev/mapper 名扫 dm/name 匹配；分区溯父。 */
    private fun canonicalDiskName(name: String, dmCandidates: List<String>): String? {
        if (blockDeviceExists(name)) return name
        resolveDmDeviceByName(name, dmCandidates)?.let { return it }
        return parentDiskCandidates(name).firstOrNull { blockDeviceExists(it) }
    }

    /** 设备存在性 = /sys/block/<name> 目录可列（非 Linux/不存在 → null）。 */
    private fun blockDeviceExists(name: String): Boolean =
        systemFiles.listDir("$SYS_BLOCK/$name") != null

    /** /sys/block/dm-N/dm/name 与 /dev/mapper/<name> 同名（含 LVM 的 -- 转义形式），直接匹配。 */
    private fun resolveDmDeviceByName(mapperName: String, dmCandidates: List<String>): String? =
        dmCandidates.filter { it.startsWith("dm-") }.firstOrNull { dm ->
            systemFiles.readFile("$SYS_BLOCK/$dm/dm/name")?.trim() == mapperName
        }

    /** sdf1 → [sdf]；nvme0n1p2 → [nvme0n1p, nvme0n1]（p 尾巴先试后丢，命中 blockDevs 为准）。 */
    private fun parentDiskCandidates(partition: String): List<String> {
        val m = TRAILING_DIGIT_RUN.find(partition) ?: return emptyList()
        val base = m.groupValues[1]
        return if (base.endsWith('p')) listOf(base, base.dropLast(1)) else listOf(base)
    }

    // ── mountinfo 解析 ────────────────────────────────────────────────────────

    /** 最长前缀匹配 rootPath 所在挂载项；mountinfo 不可读/无匹配返回 null。 */
    private fun mountFor(rootPath: String): MountEntry? {
        val text = systemFiles.readFile(MOUNTINFO_PATH) ?: return null
        val target = normalizePath(canonicalize(rootPath))
        return text.lineSequence().mapNotNull(::parseMountEntry)
            .filter { matchesPrefix(it.mountPoint, target) }
            .maxByOrNull { normalizePath(it.mountPoint).length }
    }

    private fun parseMountEntry(line: String): MountEntry? {
        val sep = line.indexOf(" - ")
        if (sep <= 0) return null
        val before = line.substring(0, sep).split(' ').filter { it.isNotEmpty() }
        val after = line.substring(sep + 3).split(' ').filter { it.isNotEmpty() }
        // before: mount-id parent major:minor root mount-point options [optional…]
        // after:  fs-type source [super-options…]
        if (before.size < 5 || after.size < 2) return null
        return MountEntry(mountPoint = unescapeOctal(before[4]), fsType = after[0], source = unescapeOctal(after[1]))
    }

    private fun matchesPrefix(mountPoint: String, target: String): Boolean {
        val mp = normalizePath(mountPoint)
        return mp == "/" || target == mp || target.startsWith("$mp/")
    }

    /** 仅作 symlink/相对路径归一（真实 FS 调用，非探测）；失败原样返回。 */
    private fun canonicalize(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    private fun normalizePath(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    /** mountinfo 将空格/制表等转义为八进制 \040 形式。 */
    private fun unescapeOctal(s: String): String =
        MOUNTINFO_OCTAL.replace(s) { m -> m.groupValues[1].toInt(8).toChar().toString() }

    // ── 其他小件 ──────────────────────────────────────────────────────────────

    private fun parsedOverride(): StorageProfile? = when (profileOverride.trim().lowercase()) {
        "", "auto" -> null
        "ssd" -> StorageProfile.SSD
        "hdd" -> StorageProfile.HDD
        "zfs" -> StorageProfile.ZFS
        else -> {
            log.warn("invalid storage.profile-override '{}' — ignored (auto)", profileOverride)
            null
        }
    }

    private fun topLevelDeviceName(source: String): String =
        source.substringAfterLast('/').substringBefore('[').trim()

    /** jdbc:sqlite:/path/db?params → /path/db；非 sqlite 数据源返回 null。 */
    private fun sqlitePathFromDatasourceUrl(url: String): String? {
        if (!url.startsWith("jdbc:sqlite:")) return null
        return url.removePrefix("jdbc:sqlite:").substringBefore('?').trim().takeIf { it.isNotEmpty() }
    }

    private fun result(
        rootPath: String,
        profile: StorageProfile,
        fsType: String? = null,
        fsTypeSource: String? = null,
        mountSource: String? = null,
        leafDevices: List<String> = emptyList(),
        rotational: Map<String, Int?> = emptyMap(),
        zfsPool: String? = null,
        zfsCacheHint: Boolean? = null,
        failureReason: String? = null,
    ) = StorageProfileResult(
        profile,
        StorageEvidence(
            rootPath, fsType, fsTypeSource, mountSource, leafDevices, rotational,
            zfsPool, zfsCacheHint, failureReason, overrideApplied = null, naturalProfile = null,
        ),
    )

    internal data class MountEntry(val mountPoint: String, val fsType: String, val source: String)

    companion object {
        private val log = LoggerFactory.getLogger(StorageProfileService::class.java)

        private const val MOUNTINFO_PATH = "/proc/self/mountinfo"
        private const val SYS_BLOCK = "/sys/block"
        private const val SYS_FS_BTRFS = "/sys/fs/btrfs"
        /** zpool status 超时自灭下限（设计要求 ≥5s）。 */
        private const val ZPOOL_TIMEOUT_MS = 5_000L
        private const val FS_SOURCE_FILE_STORE = "file-store"
        private const val FS_SOURCE_MOUNTINFO = "mountinfo"

        /** 网络 FS 类型（fuse 前缀族另按 startsWith("fuse") 命中：fuse/fuseblk/fuse.sshfs…）。 */
        private val NETWORK_FS_TYPES = setOf("nfs", "nfs4", "cifs", "cifs2", "smbfs", "smb2")
        private val TRAILING_DIGIT_RUN = Regex("^(.*?)(\\d+)$")
        private val MOUNTINFO_OCTAL = Regex("\\\\([0-7]{3})")

        /**
         * 解析 `zpool status` 输出是否存在顶层 cache（L2ARC）或 special vdev 小节。
         * 小节标签行无缩进、首 token 即 "cache"/"special"（"cache  -  -  -  -" 单行形式同样命中）。
         */
        internal fun parseZpoolAcceleratorSections(output: String): Boolean =
            output.lineSequence()
                .dropWhile { !it.startsWith("config:") }
                .drop(1)
                .any { raw -> raw.trim().takeWhile { !it.isWhitespace() } in ACCELERATOR_SECTIONS }

        private val ACCELERATOR_SECTIONS = setOf("cache", "special")
    }
}
