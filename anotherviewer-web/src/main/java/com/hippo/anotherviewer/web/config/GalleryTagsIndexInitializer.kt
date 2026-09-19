package com.hippo.anotherviewer.web.config

import jakarta.annotation.PostConstruct
import jakarta.persistence.EntityManagerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * gallery_tags.gid 索引兜底（性能快赢）：findByGid/deleteByGid 按 gid 过滤，
 * 无索引时全表扫。ddl-auto=update 不会给已存在的表补建索引，存量库靠启动时
 * `CREATE INDEX IF NOT EXISTS`（SQLite 支持，幂等）补齐；新装库的索引由实体
 * @Table(indexes=...) 建表时带上，此处的 IF NOT EXISTS 与之互不冲突。
 */
@Component
class GalleryTagsIndexInitializer(
    private val jdbcTemplate: JdbcTemplate,
    // 构造注入 EMF 仅为止排序：强制 Hibernate schema 初始化（建表）先于
    // 本 @PostConstruct，否则全新库上建索引会撞 no such table。
    entityManagerFactory: EntityManagerFactory,
) {

    @PostConstruct
    fun createGidIndex() {
        jdbcTemplate.execute(CREATE_INDEX_SQL)
    }

    companion object {
        const val CREATE_INDEX_SQL = "CREATE INDEX IF NOT EXISTS idx_gallery_tags_gid ON gallery_tags(gid)"
    }
}
