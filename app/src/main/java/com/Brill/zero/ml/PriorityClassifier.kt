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
    @Volatile private var currentModelId: String? = null

    // 取消应用启动时的预加载，避免早期 JNI/资产管理并发导致崩溃

    private val labelMap = mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW,
        "低优先级/垃圾" to Priority.LOW
    )

    // 动态构建选项：支持文件模型（已学习）与资产模型（原始）
    private fun createOptions(): TextClassifier.TextClassifierOptions? {
        return try {
            val useLearned = com.brill.zero.settings.AppSettings.getUseLearnedL1(appContext)
            val builder = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
            val id: String
            if (useLearned) {
                val selected = com.brill.zero.settings.AppSettings.getL1SelectedModelPath(appContext)
                val file = if (selected != null) java.io.File(selected) else java.io.File(appContext.noBackupFilesDir, "models/L1_learned.tflite")
                if (!file.exists() || file.length() <= 0) {
                    Log.w("PriorityClassifier", "Learned model missing; fallback to assets")
                    builder.setModelAssetPath(modelAssetPath)
                    id = "asset:$modelAssetPath"
                } else {
                    // MediaPipe Tasks BaseOptions does not provide setModelFilePath.
                    // Load the model file into a ByteBuffer and supply via setModelAssetBuffer.
                    val bytes = file.readBytes()
                    val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size)
                    buffer.put(bytes)
                    buffer.rewind()
                    builder.setModelAssetBuffer(buffer)
                    id = "file:${file.absolutePath}"
                }
            } else {
                // 资产快速校验：至少可读到一些字节
                val ok = runCatching {
                    context.assets.open(modelAssetPath).use { buf ->
                        val header = ByteArray(16)
                        buf.read(header) > 0
                    }
                }.getOrDefault(false)
                if (!ok) throw IllegalStateException("Asset unreadable or empty: $modelAssetPath")
                builder.setModelAssetPath(modelAssetPath)
                id = "asset:$modelAssetPath"
            }
            val base = builder.build()
            currentModelId = id
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
                if (classifier == null) {
                    val opts = createOptions()
                    if (opts == null) throw IllegalStateException("Model options null")
                    Log.d("PriorityClassifier", "Loading L1 model: ${currentModelId}")
                    classifier = TextClassifier.createFromOptions(appContext, opts)
                    modelAvailable = true
                    Log.d("PriorityClassifier", "L1 model loaded successfully")
                }
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
                // 若未初始化或模型选择已改变，重新加载
                val desiredId = if (com.brill.zero.settings.AppSettings.getUseLearnedL1(appContext)) {
                    val selected = com.brill.zero.settings.AppSettings.getL1SelectedModelPath(appContext)
                    val file = if (selected != null) java.io.File(selected) else java.io.File(appContext.noBackupFilesDir, "models/L1_learned.tflite")
                    "file:${file.absolutePath}"
                } else {
                    "asset:$modelAssetPath"
                }
                if (classifier == null || currentModelId != desiredId) {
                    try {
                        val opts = createOptions()
                        if (opts == null) throw IllegalStateException("Model options null")
                        classifier = TextClassifier.createFromOptions(appContext, opts)
                        modelAvailable = true
                        Log.d("PriorityClassifier", "Model initialized: $currentModelId")
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
                // 将输入文本转换为拼音以匹配端侧训练的数据分布
                val pinyinText = com.brill.zero.util.PinyinUtil.toPinyin(fullText)
                val result: TextClassifierResult = classifyLock.withLock {
                    classifier!!.classify(pinyinText)
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
        // 关键字回退仍使用原始中文文本，以保证可读性
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
