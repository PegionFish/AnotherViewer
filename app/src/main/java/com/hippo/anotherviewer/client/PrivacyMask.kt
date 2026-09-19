package com.hippo.anotherviewer.client

import com.hippo.anotherviewer.Settings

/**
 * 内容打码模式（隐私遮蔽）。与 WebUI 端 privacyMask.ts 行为对齐：
 * 标题以内容序列号 "#<gid>" 显示（gid 为站点级唯一编号，两平台一致，可据此同步/反查），
 * 图片替换为占位符，敏感文本在解析层被清洗。
 */
object PrivacyMask {
    private val MASKED_TITLE_PATTERN = Regex("^#\\d{1,12}$")

    @JvmStatic
    fun isEnabled(): Boolean = Settings.getPrivacyMaskEnabled()

    /** 打码开启时返回 "#<gid>"，与原 title 内容无关（与 WebUI maskedTitle 一致） */
    @JvmStatic
    fun maskedTitle(title: String?, gid: Long): String? =
        if (isEnabled()) "#$gid" else title

    /** 打码开启时路径只保留前 max 个字符（WebUI maskedPath 语义） */
    @JvmStatic
    fun maskedPath(path: String?, max: Int = 10): String? =
        if (isEnabled()) path?.take(max) else path

    /** 判断字符串是否是打码序列号形态 "#<数字>"（用于防止写回） */
    @JvmStatic
    fun isMaskedTitle(title: String?): Boolean =
        title != null && MASKED_TITLE_PATTERN.matches(title)
}
