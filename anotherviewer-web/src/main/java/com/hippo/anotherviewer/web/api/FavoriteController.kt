package com.hippo.anotherviewer.web.api

import com.hippo.anotherviewer.web.dto.FavoriteAddRequest
import com.hippo.anotherviewer.web.dto.FavoriteListResponse
import com.hippo.anotherviewer.web.dto.FavoriteRemoveRequest
import com.hippo.anotherviewer.web.service.FavoriteService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/favorite")
class FavoriteController(private val favoriteService: FavoriteService) {

    @GetMapping("/list")
    fun listFavorites(
        @RequestParam(defaultValue = "0") slot: Int,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") pageSize: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "false") regex: Boolean
    ): ResponseEntity<*> {
        return try {
            // W3-F4b: pageSize 查询参数贯通（对齐 /history/list 语义）。默认 50、
            // 钳制 1..200 在控制器完成——FavoriteService 自身只钳下界 >= 1
            // （默认 20 仅服务层旧调用方生效），上界必须由控制器把关。
            ResponseEntity.ok(favoriteService.listFavorites(slot, page, pageSize.coerceIn(1, 200), q = q, regex = regex))
        } catch (e: IllegalArgumentException) {
            errorEnvelope(HttpStatus.BAD_REQUEST, "REGEX_INVALID", e.message ?: "正则表达式无效")
        }
    }

    @PostMapping("/add")
    fun addFavorite(@Valid @RequestBody request: FavoriteAddRequest): ResponseEntity<Map<String, Boolean>> {
        val result = favoriteService.addFavorite(request.gid, request.token, null, request.category, request.slot)
        return ResponseEntity.ok(mapOf("success" to result))
    }

    @DeleteMapping("/remove")
    fun removeFavorite(@Valid @RequestBody request: FavoriteRemoveRequest): ResponseEntity<Map<String, Boolean>> {
        val result = favoriteService.removeFavorite(request.gid)
        return ResponseEntity.ok(mapOf("success" to result))
    }
}
