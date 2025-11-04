package com.brill.zero.llama

object Llama {
    init { 
        try { System.loadLibrary("llamajni") } catch (_: Throwable) { /* ignore for non-JNI builds */ }
    }

    interface ProgressListener {
        fun onProgress(tokensGenerated: Int, maxTokens: Int)
    }
    @Volatile private var listener: ProgressListener? = null

    @JvmStatic fun setProgressListener(l: ProgressListener) { listener = l }
    @JvmStatic fun clearProgressListener() { listener = null }

    // Called from JNI
    @JvmStatic fun onNativeProgress(tokensGenerated: Int, maxTokens: Int) {
        listener?.onProgress(tokensGenerated, maxTokens)
    }

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
