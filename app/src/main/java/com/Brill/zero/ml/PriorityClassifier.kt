package com.brill.zero.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brill.zero.domain.model.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
// ✅ MediaPipe Tasks API
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.mediapipe.tasks.text.textclassifier.TextClassifierResult
import com.google.mediapipe.tasks.components.containers.Category   // ✅ 新增

class PriorityClassifier(private val context: Context) {

    private val modelPath = "models/l1_gatekeeper_model.tflite"

    private val labelMap = mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW
    )

    private val textClassifier: TextClassifier by lazy {
        Log.i("ZeroL1-MP", "正在加载 L1 (Gatekeeper) MediaPipe 模型...")
        val startTime = SystemClock.elapsedRealtime()

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelPath)
            .build()

        val options = TextClassifier.TextClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .build()

        TextClassifier.createFromOptions(context, options).also {
            Log.i(
                "ZeroL1-MP",
                "L1 MediaPipe 模型加载完毕 (耗时: ${SystemClock.elapsedRealtime() - startTime}ms)"
            )
        }
    }

    suspend fun predictPriority(fullText: String): Priority = withContext(Dispatchers.Default) {
        val start = SystemClock.elapsedRealtime()

        if (fullText.isBlank()) {
            Log.w("ZeroL1-MP", "空文本，降级为 LOW")
            return@withContext Priority.LOW
        }

        // 运行推理（异常保护）
        val result: TextClassifierResult = runCatching { textClassifier.classify(fullText) }
            .getOrElse { e ->
                Log.e("ZeroL1-MP", "classify 失败：${e.message}", e)
                return@withContext Priority.LOW
            }

        // 0.10.x 正确取法：classificationResult → classifications → categories
        val best: Category? = result
            .classificationResult()
            .classifications()
            .firstOrNull()
            ?.categories()
            ?.maxByOrNull { it.score() }

        val mapped = best?.let { labelMap[it.categoryName()] } ?: Priority.LOW

        Log.d(
            "ZeroL1-MP",
            String.format(
                Locale.US,
                "L1 预测: '%s' (score=%.3f) -> %s (耗时: %dms)",
                best?.categoryName() ?: "N/A",
                best?.score() ?: 0f,
                mapped.name,
                SystemClock.elapsedRealtime() - start
            )
        )

        return@withContext mapped
    }
    suspend fun preload() = withContext(kotlinx.coroutines.Dispatchers.Default) {
        // 触发 lazy 初始化即可
        textClassifier.hashCode()
    }

}
