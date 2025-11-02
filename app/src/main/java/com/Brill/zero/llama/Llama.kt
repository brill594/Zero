package com.Brill.zero.llama

object Llama {
    init { System.loadLibrary("llamajni") }

    @JvmStatic external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        nGpuLayers: Int,
        threads: Int
    ): Long

    @JvmStatic external fun nativeCompletion(
        handle: Long,
        prompt: String,
        grammar: String?,      // 传 GBNF（可为 null）
        maxTokens: Int,
        temp: Float,
        topP: Float,
        seed: Int
    ): String

    @JvmStatic external fun nativeFree(handle: Long)
}
