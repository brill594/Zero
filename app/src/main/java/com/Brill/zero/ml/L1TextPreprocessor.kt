package com.brill.zero.ml

import java.text.Normalizer

/**
 * L1 文本统一预处理：
 * - 归一化 NFKC
 * - URL/EMAIL/PHONE/TRACK/CODE 归一替换
 * - 中文 → 拼音音节（单引号分隔）；若失败则转 Unicode 码点（UXXXX）
 * - 压缩空白、转小写
 */
object L1TextPreprocessor {
    private val URL_REGEX   = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("[\\w\\.-]+@[\\w\\.-]+\\.\\w+")
    private val PHONE_REGEX = Regex("(?:\\+?\\d[\\d\\- ]{6,}\\d)")
    private val CODE_REGEX  = Regex("\\b\\d{4,8}\\b")
    private val TRACK_REGEX = Regex("\\b(?:SF|YT|ZTO|JD)\\w{6,}\\b", RegexOption.IGNORE_CASE)
    private val ZH_REGEX    = Regex("[\\u4E00-\\u9FFF]+")

    fun asciiNormalizeForL1(text: String): String {
        var t = try { Normalizer.normalize(text, Normalizer.Form.NFKC) } catch (_: Throwable) { text }
        t = URL_REGEX.replace(t, "<URL>")
        t = EMAIL_REGEX.replace(t, "<EMAIL>")
        t = PHONE_REGEX.replace(t, "<PHONE>")
        t = TRACK_REGEX.replace(t, "<TRACK>")
        t = CODE_REGEX.replace(t, "<CODE>")
        t = ZH_REGEX.replace(t) { m ->
            val src = m.value
            val py = runCatching { com.brill.zero.util.PinyinUtil.toPinyinSyllables(src) }.getOrDefault("")
            if (py.isNotBlank()) py else src.codePoints().toArray().joinToString(" ") { "U%04X".format(it) }
        }
        return t.replace(Regex("\\s+"), " ").trim().lowercase()
    }
}