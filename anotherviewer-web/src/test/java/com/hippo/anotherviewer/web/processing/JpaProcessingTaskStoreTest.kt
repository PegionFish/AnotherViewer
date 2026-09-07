package com.hippo.anotherviewer.web.processing

import com.hippo.anotherviewer.web.entity.ProcessingTaskEntity
import com.hippo.anotherviewer.web.repository.ProcessingTaskRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

/**
 * C2：JpaProcessingTaskStore 对真实 SQLite（临时文件，ddl-auto=create）的行为
 * 测试——写穿 upsert 不增行、活跃/历史查询语义、页级去重 CSV 合并与脏数据容错、
 * 启动对账只改活跃行、record ↔ entity 往返无损。
 *
 * store 手动实例化（@DataJpaTest 不扫 @Component），@Transactional 由测试事务兜底。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaProcessingTaskStoreTest {

    companion object {
        private val dbDir = Files.createTempDirectory("av-processing-store")

        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${dbDir.resolve("processing-store.db")}" }
            registry.add("spring.datasource.driver-class-name") { "org.sqlite.JDBC" }
            registry.add("spring.jpa.database-platform") { "org.hibernate.community.dialect.SQLiteDialect" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create" }
        }
    }

    @Autowired
    lateinit var repo: ProcessingTaskRepository

    @PersistenceContext
    lateinit var em: EntityManager

    private lateinit var store: JpaProcessingTaskStore

    @BeforeEach
    fun setUp() {
        store = JpaProcessingTaskStore(repo)
    }

    private fun record(
        taskId: String,
        galleryId: Long = 42L,
        trigger: ProcessingTrigger = ProcessingTrigger.MANUAL,
        type: ProcessingType = ProcessingType.REMOVE_BG,
        state: TaskState = TaskState.PENDING,
        createdAt: Long = 1_000L,
        donePages: Set<Int> = emptySet(),
        errorCode: String = "",
        errorMessage: String = "",
    ) = ProcessingTaskRecord(
        taskId = taskId,
        galleryId = galleryId,
        title = "画廊 $taskId",
        trigger = trigger,
        processingType = type,
        state = state,
        pagesTotal = 10,
        pagesDone = donePages.size,
        pagesFailed = 0,
        sourceDir = "/data/downloads/g$galleryId",
        outputDir = "/cache/enhanced/$galleryId",
        epTaskIds = listOf("ep-$taskId-1", "ep-$taskId-2"),
        errorCode = errorCode,
        errorMessage = errorMessage,
        createdAt = createdAt,
        startedAt = createdAt + 10,
        finishedAt = if (state == TaskState.DONE || state == TaskState.FAILED) createdAt + 20 else 0,
        updatedAt = createdAt + 30,
        donePages = donePages,
    )

    @Test
    fun `upsert inserts then updates without growing rows`() {
        val base = record("proc-u1", state = TaskState.PENDING, createdAt = 1_000L)
        store.upsert(base)
        assertEquals(1, repo.count())

        val updated = base.copy(
            state = TaskState.PROCESSING,
            pagesDone = 3,
            donePages = setOf(0, 1, 2),
            startedAt = 1_500L,
            updatedAt = 1_600L,
        )
        store.upsert(updated)

        assertEquals(1, repo.count(), "同 taskId upsert 不得增行")
        assertEquals(updated, store.findByTaskId("proc-u1"))
    }

    @Test
    fun `findActive returns only PENDING and PROCESSING sorted newest first`() {
        store.upsert(record("proc-a1", state = TaskState.PENDING, createdAt = 100L))
        store.upsert(record("proc-a2", state = TaskState.PROCESSING, createdAt = 300L))
        store.upsert(record("proc-a3", state = TaskState.DONE, createdAt = 200L))
        store.upsert(record("proc-a4", state = TaskState.FAILED, createdAt = 400L))

        val active = store.findActive()

        assertEquals(listOf("proc-a2", "proc-a1"), active.map { it.taskId }, "只留活跃且新→旧")
    }

    @Test
    fun `findHistory paginates filters by state and clamps size and page`() {
        val states = listOf(
            TaskState.DONE, TaskState.DONE, TaskState.DONE,
            TaskState.DONE, TaskState.DONE, TaskState.FAILED, TaskState.FAILED,
        )
        states.forEachIndexed { i, s -> store.upsert(record("proc-h$i", state = s, createdAt = (i + 1) * 100L)) }

        // 全量分页：新→旧。
        val page0 = store.findHistory(0, 3)
        assertEquals(7, page0.total)
        assertEquals(3, page0.items.size)
        assertEquals(listOf("proc-h6", "proc-h5", "proc-h4"), page0.items.map { it.taskId })
        assertEquals(0, page0.page)
        assertEquals(3, page0.size)

        val page1 = store.findHistory(1, 3)
        assertEquals(listOf("proc-h3", "proc-h2", "proc-h1"), page1.items.map { it.taskId })

        // state 过滤。
        val failed = store.findHistory(0, 50, TaskState.FAILED)
        assertEquals(2, failed.total)
        assertEquals(listOf("proc-h6", "proc-h5"), failed.items.map { it.taskId })

        // size 钳 1..500，page 钳 ≥0。
        assertEquals(1, store.findHistory(0, 0).size)
        assertEquals(1, store.findHistory(0, -5).items.size)
        assertEquals(500, store.findHistory(0, 9999).size)
        assertEquals(0, store.findHistory(-3, 10).page)
    }

    @Test
    fun `findCompletedPages merges csv across rows and tolerates dirty data`() {
        // 同 (gallery, type) 多行 DONE：CSV 合并（含重复页、空白容错）。
        store.upsert(record("proc-c1", state = TaskState.DONE, donePages = setOf(0, 2)))
        store.upsert(record("proc-c2", state = TaskState.DONE, donePages = setOf(1, 2)))
        store.upsert(record("proc-c3", state = TaskState.DONE, donePages = emptySet()))
        // 脏数据走直插（写穿路径写不出非法 CSV）：空串/非数字段必须被跳过。
        repo.save(entityOf("proc-c4", state = "DONE", donePagesCsv = "4,abc,,5"))
        // 非本画廊 / 非本类型 / 非 DONE 一律不计入。
        repo.save(entityOf("proc-c5", state = "DONE", donePagesCsv = "9", galleryId = 999L))
        repo.save(entityOf("proc-c6", state = "DONE", donePagesCsv = "8", type = "UPSCALE_2X"))
        repo.save(entityOf("proc-c7", state = "FAILED", donePagesCsv = "7"))

        assertEquals(setOf(0, 1, 2, 4, 5), store.findCompletedPages(42L, ProcessingType.REMOVE_BG))
        assertEquals(emptySet<Int>(), store.findCompletedPages(42L, ProcessingType.UPSCALE_4X), "无匹配行返回空集")
    }

    @Test
    fun `markInterruptedAtStartup rewrites only active rows with EP_INTERRUPTED`() {
        val before = mapOf(
            "proc-m1" to record("proc-m1", state = TaskState.PROCESSING, createdAt = 100L, errorMessage = "上游正常推进"),
            "proc-m2" to record("proc-m2", state = TaskState.PENDING, createdAt = 200L),
            "proc-m3" to record("proc-m3", state = TaskState.DONE, createdAt = 300L),
            "proc-m4" to record("proc-m4", state = TaskState.FAILED, createdAt = 400L, errorCode = "EP_TIMEOUT", errorMessage = "上游超时"),
        )
        before.values.forEach { store.upsert(it) }

        val rewritten = store.markInterruptedAtStartup()
        assertEquals(2, rewritten, "只改写 PENDING/PROCESSING 行")

        // 批量 JPQL 绕过持久化上下文，先 flush/clear 再读库。
        em.flush()
        em.clear()

        val m1 = store.findByTaskId("proc-m1")!!
        assertEquals(TaskState.FAILED, m1.state)
        assertEquals("EP_INTERRUPTED", m1.errorCode)
        assertTrue(m1.errorMessage.contains("上游正常推进") && m1.errorMessage.contains("服务重启中断"), "追加而非覆盖文案")
        assertTrue(m1.finishedAt > 0 && m1.updatedAt >= m1.finishedAt)

        val m2 = store.findByTaskId("proc-m2")!!
        assertEquals(TaskState.FAILED, m2.state)
        assertEquals("EP_INTERRUPTED", m2.errorCode)

        // 终态行原样保留。
        assertEquals(before["proc-m3"], store.findByTaskId("proc-m3"))
        assertEquals(before["proc-m4"], store.findByTaskId("proc-m4"))

        // 已无活跃行：再跑一次改写 0 行。
        assertEquals(0, store.markInterruptedAtStartup())
    }

    @Test
    fun `record to entity and back is lossless`() {
        val base = ProcessingTaskRecord(
            taskId = "proc-rt",
            galleryId = 77L,
            title = "往返无损样例",
            trigger = ProcessingTrigger.SCHEDULED,
            processingType = ProcessingType.UPSCALE_4X,
            state = TaskState.DONE,
            pagesTotal = 12,
            pagesDone = 10,
            pagesFailed = 2,
            sourceDir = "/data/downloads/g77",
            outputDir = "/cache/enhanced/77",
            epTaskIds = listOf("ep-1", "ep-2"),
            errorCode = "INTERNAL",
            errorMessage = "上游 500",
            createdAt = 111L,
            startedAt = 222L,
            finishedAt = 333L,
            updatedAt = 444L,
            donePages = setOf(10, 0, 5),
        )
        store.upsert(base)

        // 落库形状：枚举存 name()、集合存 CSV（donePages 升序）。
        val row = repo.findByTaskId("proc-rt")!!
        assertEquals("SCHEDULED", row.trigger)
        assertEquals("UPSCALE_4X", row.processingType)
        assertEquals("DONE", row.state)
        assertEquals("ep-1,ep-2", row.epTaskIds)
        assertEquals("0,5,10", row.donePages)

        assertEquals(base, store.findByTaskId("proc-rt"), "record ↔ entity 往返字段无损")
        assertNull(store.findByTaskId("proc-不存在"))
    }

    @Test
    fun `unknown enum literals in db fall back to record defaults`() {
        repo.save(entityOf("proc-x1", state = "CANCELLED", trigger = "TIME_TRAVEL", type = "HOLO_DECK"))

        val r = store.findByTaskId("proc-x1")!!
        assertEquals(TaskState.PENDING, r.state)
        assertEquals(ProcessingTrigger.MANUAL, r.trigger)
        assertEquals(ProcessingType.UPSCALE_2X, r.processingType)
    }

    /** 直插原始 entity（绕过 record 映射），用于构造写穿路径造不出的库内形状。 */
    private fun entityOf(
        taskId: String,
        state: String,
        donePagesCsv: String = "",
        galleryId: Long = 42L,
        type: String = "REMOVE_BG",
        trigger: String = "MANUAL",
    ) = ProcessingTaskEntity().apply {
        this.taskId = taskId
        this.galleryId = galleryId
        this.trigger = trigger
        this.processingType = type
        this.state = state
        this.donePages = donePagesCsv
        this.createdAt = 500L
        this.updatedAt = 500L
    }
}
