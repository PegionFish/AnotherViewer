package com.hippo.anotherviewer.web.processing.ep

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hippo.anotherviewer.web.service.ServerConfigService
import com.hippo.anotherviewer.web.service.WebProxyManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * OkHttpEntryPointClient 实测契约单测（手册 §1）：端点形状、Bearer 鉴权、
 * multipart 上传、状态归一化、错误信封阶梯（信封→code / JSON 兜底→UNKNOWN /
 * 非 JSON→HTTP_<status>）、429/5xx 退避、产物 302 跟随、cancel 永不抛（D3）。
 */
class OkHttpEntryPointClientTest {

    private lateinit var server: MockWebServer
    private val json = jacksonObjectMapper()

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 代理关停的 WebProxyManager（NO_PROXY 直连 MockWebServer）。 */
    private fun directProxyManager(): WebProxyManager {
        val config = mock(ServerConfigService::class.java)
        `when`(config.getBoolean(WebProxyManager.KEY_ENABLED, false)).thenReturn(false)
        `when`(config.get(anyString(), anyString())).thenReturn("")
        return WebProxyManager(config)
    }

    /** retryBaseDelayMs=1：退避路径瞬时完成，只验证重试次数。 */
    private fun client() = OkHttpEntryPointClient(directProxyManager(), retryBaseDelayMs = 1)

    private fun creds(token: String = "tok") = EntryPointCreds(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = token,
    )

    private fun imageBytes(format: String, alpha: Boolean = false): ByteArray {
        val type = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val image = BufferedImage(4, 4, type)
        val graphics = image.createGraphics()
        graphics.color = Color.RED
        graphics.fillRect(0, 0, 4, 4)
        graphics.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, format, out)) { "ImageIO writer missing for $format" }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // 能力发现
    // ------------------------------------------------------------------

    @Test
    fun `capabilities sends bearer token and parses flat catalog`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"capabilities":[
                  {"module_id":"rembg","capability":"remove_bg","input_type":"image",
                   "output_type":"image","max_file_size_mb":50,
                   "params":{"alpha_matting":{"type":"bool","default":false}}},
                  {"module_id":"realesr","capability":"upscale","input_type":"video",
                   "output_type":"video","max_file_size_mb":4096,"params":{}}
                ]}
                """.trimIndent(),
            ),
        )

        val caps = client().capabilities(creds())

        val request = server.takeRequest()
        assertEquals("/api/v1/capabilities", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals(2, caps.size)
        assertEquals(
            EpCapability(
                "rembg", "remove_bg", "image", "image", 50,
                mapOf("alpha_matting" to mapOf("type" to "bool", "default" to false)),
            ),
            caps[0],
        )
        assertEquals("rembg", caps[0].moduleId)
        assertEquals(4096L, caps[1].maxFileSizeMb)
    }

    @Test
    fun `empty token omits authorization header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"capabilities":[]}"""))

        val caps = client().capabilities(creds(token = ""))

        assertTrue(caps.isEmpty())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    @Test
    fun `upload posts multipart file field and returns reference`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"workspace/uploads/in.png"}"""))
        val input = Files.write(tempDir.resolve("page.png"), "page-bytes".toByteArray())

        val reference = client().upload(creds(), input)

        assertEquals("workspace/uploads/in.png", reference)
        val request = server.takeRequest()
        assertEquals("/api/files", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val payload = request.body.readUtf8()
        assertTrue(payload.contains("""name="file""""))
        assertTrue(payload.contains("""filename="page.png""""))
        assertTrue(payload.contains("page-bytes"))
    }

    @Test
    fun `upload retries 429 with backoff then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"path":"ref"}"""))
        val input = Files.write(tempDir.resolve("a.png"), byteArrayOf(1))

        assertEquals("ref", client().upload(creds(), input))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `upload connection failure surfaces EP_UNREACHABLE`() {
        val dead = EntryPointCreds(baseUrl = "http://127.0.0.1:1", token = "tok")
        val input = Files.write(tempDir.resolve("a.png"), byteArrayOf(1))

        val ex = assertThrows(EpException::class.java) {
            runBlocking { client().upload(dead, input) }
        }
        assertEquals("EP_UNREACHABLE", ex.code)
        assertEquals(0, ex.httpStatus)
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    @Test
    fun `submit posts input_path json and parses task id`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(202)
                .setBody("""{"queue_position":1,"task_id":"task-20260907-171133-0005"}"""),
        )

        val result = client().submit(
            creds(), "rembg", "remove_bg", "workspace/uploads/in.png",
            mapOf("scale_factor" to 2),
        )

        assertEquals("task-20260907-171133-0005", result.taskId)
        assertEquals(1, result.queuePosition)
        val request = server.takeRequest()
        assertEquals("/api/v1/inference/rembg/remove_bg", request.path)
        assertEquals(
            json.readTree("""{"input_path":"workspace/uploads/in.png","params":{"scale_factor":2}}"""),
            json.readTree(request.body.readUtf8()),
        )
    }

    @Test
    fun `submit retries on 5xx then succeeds`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"task_id":"t2"}"""))

        val result = client().submit(creds(), "rembg", "remove_bg", "ref")

        assertEquals("t2", result.taskId)
        assertNull(result.queuePosition)
        assertEquals(2, server.requestCount)
    }

    // ------------------------------------------------------------------
    // 轮询与状态归一化（D4）
    // ------------------------------------------------------------------

    @Test
    fun `result maps five states plus unknown to normalized states`() = runBlocking {
        val cases = listOf(
            "queued" to EpTaskState.QUEUED,
            "running" to EpTaskState.RUNNING,
            "completed" to EpTaskState.COMPLETED,
            "failed" to EpTaskState.FAILED,
            "cancelled" to EpTaskState.CANCELLED,
            "whats-this" to EpTaskState.UNKNOWN,
        )
        cases.forEach { (raw, _) ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"task_id":"t1","status":"$raw",
                        "outputs":[{"node_id":"input","url":"/in"},{"node_id":"output","url":"/out"}]}""",
                ),
            )
        }
        val client = client()

        cases.forEach { (raw, expected) ->
            val result = client.result(creds(), "t1")
            assertEquals(expected, result.state, "raw status '$raw' must normalize to $expected")
            assertEquals(listOf(EpArtifact("input", "/in"), EpArtifact("output", "/out")), result.outputs)
        }
    }

    @Test
    fun `result failure carries error text for terminal states`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"task_id":"t1","status":"failed","error":"model crashed"}"""),
        )

        val result = client().result(creds(), "t1")

        assertEquals(EpTaskState.FAILED, result.state)
        assertEquals("model crashed", result.error)
        assertTrue(result.isTerminal)
    }

    // ------------------------------------------------------------------
    // 错误信封阶梯
    // ------------------------------------------------------------------

    @Test
    fun `error envelope surfaces machine code and http status`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"invalid token"}}"""),
        )

        val ex = assertThrows(EpException::class.java) {
            runBlocking { client().result(creds(), "t1") }
        }
        assertEquals("UNAUTHORIZED", ex.code)
        assertEquals(401, ex.httpStatus)
        assertTrue(ex.message!!.contains("invalid token"))
    }

    @Test
    fun `non envelope json body maps to UNKNOWN and non json body to HTTP status`() {
        // 部署版 /api/v1/tasks 404 中文兜底体（实测 §1）→ 信封解析失败 → UNKNOWN。
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"error":"接口不存在"}"""),
        )
        val unknown = assertThrows(EpException::class.java) {
            runBlocking { client().result(creds(), "t1") }
        }
        assertEquals("UNKNOWN", unknown.code)
        assertEquals(404, unknown.httpStatus)

        // 非 JSON 体 → HTTP_<status>。
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal boom"))
        val http = assertThrows(EpException::class.java) {
            runBlocking { client().result(creds(), "t1") }
        }
        assertEquals("HTTP_500", http.code)
    }

    // ------------------------------------------------------------------
    // 退避上限
    // ------------------------------------------------------------------

    @Test
    fun `429 exhausting retries throws and stops after max retries`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(429)) }
        val input = Files.write(tempDir.resolve("a.png"), byteArrayOf(1))

        val ex = assertThrows(EpException::class.java) {
            runBlocking { client().upload(creds(), input) }
        }
        assertEquals("HTTP_429", ex.code)
        assertEquals(4, server.requestCount) // 首次 + 至多 3 次重试
    }

    // ------------------------------------------------------------------
    // 产物下载（302 跟随）
    // ------------------------------------------------------------------

    @Test
    fun `download follows 302 redirect and writes file stream`() = runBlocking {
        val png = imageBytes("png")
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "/api/tasks/t1/artifacts/output"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(png)))
        val target = tempDir.resolve("artifact.png")

        client().downloadToFile(creds(), "/api/tasks/t1/artifacts/output", target)

        assertTrue(Files.exists(target))
        assertTrue(Files.readAllBytes(target).contentEquals(png))
        assertEquals("/api/tasks/t1/artifacts/output", server.takeRequest().path)
        assertEquals("/api/tasks/t1/artifacts/output", server.takeRequest().path) // 302 落点
    }

    // ------------------------------------------------------------------
    // 取消（D3：永不抛）
    // ------------------------------------------------------------------

    @Test
    fun `cancel never throws on 404 chinese fallback or connection reset`() {
        // 404 + 中文兜底体（部署版 /api/v1/tasks/* 整体不存在）。
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"接口不存在"}"""))
        // 连接被对端立即断开。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        client().cancel(creds(), "task-1")
        client().cancel(creds(), "task-2")
    }
}
