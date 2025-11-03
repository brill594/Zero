package com.brill.zero.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brill.zero.domain.model.Priority
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.mediapipe.tasks.text.textclassifier.TextClassifierResult
import com.google.mediapipe.tasks.components.containers.Category
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PriorityClassifier(private val context: Context) {
    private val appContext = context.applicationContext
    // Feature flag：开启 L1 的 MediaPipe 使用（已使用 CPU 委托以提高稳定性）
    private val useL1MediaPipe: Boolean = true

    private val modelAssetPath = "models/l1_gatekeeper_model.tflite"

    // 取消应用启动时的预加载，避免早期 JNI/资产管理并发导致崩溃

    private val labelMap = mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW
    )

    // 仅构造一次，避免频繁加载 native
    private val options by lazy {
        try {
            Log.d("PriorityClassifier", "Creating model options for: $modelAssetPath")
            // 资产快速校验：至少可读到一些字节，避免空文件/占位导致 native 崩溃
            val ok = runCatching {
                context.assets.open(modelAssetPath).use { buf ->
                    val header = ByteArray(16)
                    buf.read(header) > 0
                }
            }.getOrDefault(false)
            if (!ok) {
                throw IllegalStateException("Asset unreadable or empty: $modelAssetPath")
            }
            val base = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)   // 直接用 assets
                .setDelegate(Delegate.CPU)           // 显式使用 CPU 委托，避免 NNAPI/GPU 原生崩溃
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
    private val classifyLock = ReentrantLock()

    /** 可选：提前加载，放到 IO 线程 */
    suspend fun preload() = withContext(Dispatchers.IO) {
        try {
            if (!useL1MediaPipe) {
                Log.i("PriorityClassifier", "L1 MediaPipe disabled; using keyword fallback")
                modelAvailable = false
                return@withContext
            }
            classifyLock.withLock {
                if (classifier == null && options != null) {
                    Log.d("PriorityClassifier", "Loading L1 model from assets: $modelAssetPath")
                    classifier = TextClassifier.createFromOptions(appContext, options!!)
                    modelAvailable = true
                    Log.d("PriorityClassifier", "L1 model loaded successfully")
                }
            }
            if (options == null) {
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
        if (useL1MediaPipe) {
            classifyLock.withLock {
                if (classifier == null && options != null) {
                    try {
                        classifier = TextClassifier.createFromOptions(appContext, options!!)
                        modelAvailable = true
                        Log.d("PriorityClassifier", "Model initialized successfully")
                    } catch (e: Exception) {
                        Log.e("PriorityClassifier", "Failed to initialize model: ${e.message}")
                        modelAvailable = false
                    }
                }
            }
        } else {
            modelAvailable = false
        }

        // Use ML model for classification
        if (useL1MediaPipe && modelAvailable && classifier != null) {
            try {
                val result: TextClassifierResult = classifyLock.withLock {
                    classifier!!.classify(fullText)
                }

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
    private fun classifyByKeywords(text: String): Priority {
        val lowerText = text.lowercase()
        
        // Define keyword patterns for each priority level
        val highPriorityKeywords = listOf("紧急", "重要", "立即", "马上", "尽快", "截止", "过期", "超时", "失败", "错误", "异常", "报警", "警告", "危险", "严重")
        val mediumPriorityKeywords = listOf("通知", "提醒", "更新", "确认", "审核", "审批", "预约", "会议", "任务", "工作", "项目")
        val lowPriorityKeywords = listOf("广告", "推广", "营销", "优惠", "促销", "新闻", "资讯", "社交", "闲聊", "娱乐", "游戏")
        
        // Count keyword matches for each priority level
        val highScore = highPriorityKeywords.count { keyword -> lowerText.contains(keyword) }
        val mediumScore = mediumPriorityKeywords.count { keyword -> lowerText.contains(keyword) }
        val lowScore = lowPriorityKeywords.count { keyword -> lowerText.contains(keyword) }
        
        // Return priority with highest score, default to LOW
        return when {
            highScore > mediumScore && highScore > lowScore -> Priority.HIGH
            mediumScore > highScore && mediumScore > lowScore -> Priority.MEDIUM
            else -> Priority.LOW
        }
    }
}
