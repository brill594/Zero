package com.brill.zero.util

import android.os.Build
import android.util.Log

/**
 * 将中文文本转换为拼音（不带声调，ASCII），保留空格分词。
 * - 使用 ICU Transliterator（API 24+）执行：Han-Latin → Latin-ASCII → Lower → 移除标点
 * - 对低版本设备，直接返回原文以保证兼容性
 */
object PinyinUtil {
    private const val TAG = "PinyinUtil"

    fun toPinyin(text: String): String {
        if (text.isEmpty()) return text
        return if (Build.VERSION.SDK_INT >= 24) {
            try {
                val id = "Han-Latin; Latin-ASCII; Lower(); [^\\p{L}\\p{N}\\s] Remove"
                val clazz = Class.forName("android.icu.text.Transliterator")
                val getInstance = clazz.getMethod("getInstance", String::class.java)
                val transliterator = getInstance.invoke(null, id)
                val transliterate = clazz.getMethod("transliterate", String::class.java)
                val out = transliterate.invoke(transliterator, text) as String
                out.replace("\n", " ")
                    .replace("\t", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } catch (e: Exception) {
                Log.w(TAG, "ICU transliteration failed: ${e.message}")
                // 回退：简单移除标点并小写
                text.lowercase()
                    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
        } else {
            // 低版本兼容：不转换
            text
        }
    }
}