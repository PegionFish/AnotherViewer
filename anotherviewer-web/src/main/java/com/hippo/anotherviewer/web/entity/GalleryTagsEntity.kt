package com.hippo.anotherviewer.web.entity

import jakarta.persistence.*

@Entity
// gid 是所有打标读写（findByGid / findByGidAndTag / deleteByGid）的过滤列，
// 无索引即全表扫。新装库由 ddl-auto 建表时带上该索引；存量库靠启动时的
// GalleryTagsIndexInitializer（CREATE INDEX IF NOT EXISTS）补齐。
@Table(
    name = "gallery_tags",
    indexes = [Index(name = "idx_gallery_tags_gid", columnList = "gid")],
)
class GalleryTagsEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false)
    var gid: Long = 0

    @Column(nullable = false, length = 128)
    var tag: String = ""

    @Column(length = 256)
    var tagNamespace: String? = null
}
