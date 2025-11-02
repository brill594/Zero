package com.Brill.zero.ml


object TextPreprocessor {
    fun normalize(text: String?): String = text
        ?.replace("\n", " ")
        ?.replace("\\s+".toRegex(), " ")
        ?.trim()
        ?.lowercase()
        ?: ""
}