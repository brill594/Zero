package com.brill.zero.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brill.zero.domain.model.Priority
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.mediapipe.tasks.text.textclassifier.TextClassifierResult
import com.google.mediapipe.tasks.components.containers.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PriorityClassifier(private val context: Context) {

    private val modelAssetPath = "models/l1_gatekeeper_model.tflite"

    init {
        // 初始化时先尝试加载模型
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("PriorityClassifier", "Initializing with model: $modelAssetPath")
            preload()
        }
    }

    private val labelMap = mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW
    )

    // 仅构造一次，避免频繁加载 native
    private val options by lazy {
        try {
            Log.d("PriorityClassifier", "Creating model options for: $modelAssetPath")
            val base = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)   // 直接用 assets
                .build()
            TextClassifier.TextClassifierOptions.builder()
                .setBaseOptions(base)
                .build()
        } catch (e: Exception) {
            Log.e("PriorityClassifier", "Failed to create model options: ${e.message}", e)
            null
        }
    }

    @Volatile private var classifier: TextClassifier? = null
    @Volatile private var modelAvailable = false

    /** 可选：提前加载，放到 IO 线程 */
    suspend fun preload() = withContext(Dispatchers.IO) {
        try {
            if (classifier == null && options != null) {
                Log.d("PriorityClassifier", "Loading L1 model from assets: $modelAssetPath")
                classifier = TextClassifier.createFromOptions(context, options!!)
                modelAvailable = true
                Log.d("PriorityClassifier", "L1 model loaded successfully")
            } else if (options == null) {
                Log.e("PriorityClassifier", "Model options are null - cannot load model")
                modelAvailable = false
            }
        } catch (e: Exception) {
            Log.e("PriorityClassifier", "Failed to load L1 model: ${e.message}", e)
            modelAvailable = false
        }
    }

    /** 预测（IO 线程执行，避免阻塞主线程） */
    suspend fun predictPriority(fullText: String): Priority = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()

        // Ensure classifier is initialized
        if (classifier == null && options != null) {
            try {
                classifier = TextClassifier.createFromOptions(context, options!!)
                modelAvailable = true
                Log.d("PriorityClassifier", "Model initialized successfully")
            } catch (e: Exception) {
                Log.e("PriorityClassifier", "Failed to initialize model: ${e.message}")
                modelAvailable = false
            }
        }

        // Use ML model for classification
        if (modelAvailable && classifier != null) {
            try {
                val result: TextClassifierResult = classifier!!.classify(fullText)

                // Get the best classification result
                val best: Category? = result
                    .classificationResult()
                    .classifications()
                    .firstOrNull()
                    ?.categories()
                    ?.maxByOrNull { it.score() }

                val categoryName = best?.categoryName()
                val mapped = labelMap[categoryName] ?: Priority.LOW

                Log.d(
                    "ZeroL1-MP",
                    "L1 预测: '${categoryName ?: "N/A"}' " +
                            "(score=${"%.2f".format(best?.score() ?: 0f)}) -> $mapped " +
                            "(${SystemClock.elapsedRealtime() - start}ms)"
                )
                return@withContext mapped
            } catch (e: Exception) {
                Log.e("PriorityClassifier", "Model classification failed: ${e.message}", e)
                modelAvailable = false
                // Continue to fallback
            }
        }

        // Fallback to keyword-based classification only if model fails
        val fallbackPriority = classifyByKeywords(fullText)
        Log.w(
            "ZeroL1-MP",
            "L1 预测 (keyword fallback): $fallbackPriority " +
                    "(${SystemClock.elapsedRealtime() - start}ms) - Model unavailable"
        )
        return@withContext fallbackPriority
    }

    /** Keyword-based fallback classification */
    private val keywordPatterns =  mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW
    )

    private fun classifyByKeywords(text: String): Priority {
        val lowerText = text.lowercase()
        
        // Count keyword matches for each priority level
        val scores = keywordPatterns.mapValues { (_, keywords) ->
            keywords.count { keyword -> lowerText.contains(keyword) }
        }
        
        // Return priority with highest score, default to LOW
        return scores.maxByOrNull { it.value }?.key ?: Priority.LOW
    }
}
