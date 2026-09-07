package com.hippo.anotherviewer.web.processing.ep

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hippo.anotherviewer.web.processing.ProcessingType
import com.hippo.anotherviewer.web.service.ServerConfigService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [ProcessingType] → EntryPoint `module×capability` 的映射解析器。
 *
 * 映射表来自 serverConfig `processing.type_mapping`（JSON，键为
 * `ProcessingType.name()`）：
 * ```
 * {"REMOVE_BG":{"module":"rembg","capability":"remove_bg","params":{}},
 *  "UPSCALE_2X":{"module":"realesr","capability":"upscale","params":{"scale_factor":2}},
 *  "UPSCALE_4X":{"module":"realesr","capability":"upscale","params":{"scale_factor":4}}}
 * ```
 * 键缺省时使用内置默认表（D5）：REMOVE_BG→rembg/remove_bg、
 * UPSCALE_2X/4X→realesr/upscale{scale_factor}；DENOISE / DENOISE_UPSCALE
 * 无映射——无映射类型对本处理器不可见（不出现在 [mappedTypes]）。
 * 配置 JSON 按键叠加到默认表之上；整段非法 JSON → WARN + 全默认。
 *
 * 远端能力目录 [capabilities] 带内存缓存（TTL 默认 60s）；客户端报
 * MODULE_NOT_FOUND / CAPABILITY_NOT_FOUND 时强制失效缓存并重试一次
 * （部署目录可能刚扩容）。
 */
@Component
class CapabilityMapper(
    private val serverConfigService: ServerConfigService,
    private val client: EntryPointClient,
    /** 目录/映射缓存 TTL（毫秒）；测试可注入小值。 */
    private val cacheTtlMs: Long = 60_000,
) {

    private val logger = LoggerFactory.getLogger(CapabilityMapper::class.java)
    private val json: ObjectMapper = jacksonObjectMapper()

    /** 单个 ProcessingType 的 EntryPoint 提交参数包。 */
    data class Mapping(
        val moduleId: String,
        val capability: String,
        val params: Map<String, Any?> = emptyMap(),
    )

    // ------------------------------------------------------------------
    // 映射解析（type_mapping 配置 → Mapping）
    // ------------------------------------------------------------------

    /**
     * 解析类型映射；无映射返回 null（调用方不得路由该类型到 EntryPoint）。
     * 每次读取 serverConfig（单行 SQLite 读，页级调用成本可忽略），
     * 保证设置改动即时生效。
     */
    fun resolve(type: ProcessingType): Mapping? = mappingTable()[type]

    /**
     * 当前已映射的类型集合（EntryPointProcessor.capabilities 的数据源）。
     * 60s 内存缓存——乐观值：改动最迟一个 TTL 后可见。
     */
    fun mappedTypes(): Set<ProcessingType> {
        mappedTypesCache?.let { (value, at) ->
            if (now() - at < cacheTtlMs) return value
        }
        synchronized(mappedTypesLock) {
            mappedTypesCache?.let { (value, at) ->
                if (now() - at < cacheTtlMs) return value
            }
            val value = mappingTable().keys.toSet()
            mappedTypesCache = value to now()
            return value
        }
    }

    /** 默认映射 + type_mapping 配置叠加；配置非法时整段回退默认并 WARN。 */
    private fun mappingTable(): Map<ProcessingType, Mapping> {
        val text = serverConfigService.get(ServerConfigService.KEY_TYPE_MAPPING).trim()
        if (text.isEmpty()) return DEFAULT_MAPPINGS
        val table = DEFAULT_MAPPINGS.toMutableMap()
        return try {
            val root = json.readTree(text)
            if (!root.isObject) {
                logger.warn("processing.type_mapping is not a JSON object; using built-in defaults (D5)")
                return DEFAULT_MAPPINGS
            }
            root.fieldNames().forEach { key ->
                val type = runCatching { ProcessingType.valueOf(key) }.getOrNull()
                if (type == null) {
                    logger.warn("processing.type_mapping has unknown ProcessingType key '{}'; ignored", key)
                    return@forEach
                }
                val entry = root[key]
                val moduleId = entry["module"]?.asText()?.trim().orEmpty()
                val capability = entry["capability"]?.asText()?.trim().orEmpty()
                if (moduleId.isEmpty() || capability.isEmpty()) {
                    logger.warn(
                        "processing.type_mapping['{}'] missing module/capability; entry ignored", key,
                    )
                    return@forEach
                }
                val params = entry["params"]?.takeIf { it.isObject }
                    ?.let { json.convertValue(it, object : TypeReference<Map<String, Any?>>() {}) }
                    ?: emptyMap()
                table[type] = Mapping(moduleId, capability, params)
            }
            table
        } catch (e: Exception) {
            logger.warn("processing.type_mapping is invalid JSON ({}); using built-in defaults (D5)", e.message)
            DEFAULT_MAPPINGS
        }
    }

    // ------------------------------------------------------------------
    // 远端能力目录（带 TTL 缓存 + NOT_FOUND 强刷重试一次）
    // ------------------------------------------------------------------

    /**
     * 能力发现（带缓存）。缓存未过期直接返回；[EpException.code] 为
     * MODULE_NOT_FOUND / CAPABILITY_NOT_FOUND 时失效缓存并强制刷新重试一次。
     * 刷新在锁外执行（suspend 调用不可进临界区）——并发 miss 可能产生
     * 重复的目录 GET，对只读目录无害（后写者胜，值等价）。
     */
    suspend fun capabilities(creds: EntryPointCreds): List<EpCapability> {
        catalogCache?.let { (value, at) ->
            if (now() - at < cacheTtlMs) return value
        }
        return fetchAndCache(creds)
    }

    private suspend fun fetchAndCache(creds: EntryPointCreds): List<EpCapability> =
        try {
            cacheCatalog(client.capabilities(creds))
        } catch (e: EpException) {
            if (e.code != "MODULE_NOT_FOUND" && e.code != "CAPABILITY_NOT_FOUND") throw e
            logger.warn(
                "EntryPoint catalog said {} (HTTP {}); refreshing capabilities once", e.code, e.httpStatus,
            )
            cacheCatalog(client.capabilities(creds))
        }

    private fun cacheCatalog(caps: List<EpCapability>): List<EpCapability> {
        catalogCache = caps to now()
        return caps
    }

    @Volatile
    private var catalogCache: Pair<List<EpCapability>, Long>? = null

    @Volatile
    private var mappedTypesCache: Pair<Set<ProcessingType>, Long>? = null
    private val mappedTypesLock = Any()

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** 内置默认映射（D5）。DENOISE / DENOISE_UPSCALE 无映射。 */
        val DEFAULT_MAPPINGS: Map<ProcessingType, Mapping> = mapOf(
            ProcessingType.REMOVE_BG to Mapping("rembg", "remove_bg"),
            ProcessingType.UPSCALE_2X to Mapping("realesr", "upscale", mapOf("scale_factor" to 2)),
            ProcessingType.UPSCALE_4X to Mapping("realesr", "upscale", mapOf("scale_factor" to 4)),
        )
    }
}
