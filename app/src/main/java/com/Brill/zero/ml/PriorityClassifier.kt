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
import com.brill.zero.ml.L1NaiveBayes
import com.brill.zero.ml.L1TextPreprocessor
import java.io.File
import java.text.Normalizer
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

    // 统一 AWE ASCII 预处理：与训练保持一致
    private fun preprocessForAscii(text: String): String {
        return L1TextPreprocessor.asciiNormalizeForL1(text)
    }

    // 动态构建选项：支持文件模型（已学习）与资产模型（原始）
    private fun createOptions(): TextClassifier.TextClassifierOptions? {
        return try {
            val useLearned = com.brill.zero.settings.AppSettings.getUseLearnedL1(appContext)
            val builder = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
            val id: String
            if (useLearned) {
                val selected = com.brill.zero.settings.AppSettings.getL1SelectedModelPath(appContext)
                val isJson = selected?.lowercase()?.endsWith(".json") == true
                if (isJson) {
                    // 防御：若选择的是 JSON（NB）模型，则始终回退到资产 TFLite
                    Log.w("PriorityClassifier", "Selected JSON model; fallback to asset TFLite for MediaPipe")
                    builder.setModelAssetPath(modelAssetPath)
                    id = "asset:$modelAssetPath"
                } else {
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
    @Volatile private var nbModel: L1NaiveBayes.Model? = null
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

        // Ensure classifier is initialized（或 NB 模型准备好）
        if (useL1MediaPipe) {
            classifyLock.withLock {
                val useLearned = com.brill.zero.settings.AppSettings.getUseLearnedL1(appContext)
                val selected = com.brill.zero.settings.AppSettings.getL1SelectedModelPath(appContext)
                val isJson = useLearned && selected?.lowercase()?.endsWith(".json") == true

                if (isJson) {
                    val path = selected ?: return@withLock
                    val desiredId = "nb:$path"
                    if (currentModelId != desiredId || nbModel == null) {
                        nbModel = L1NaiveBayes.loadModel(File(path))
                        currentModelId = desiredId
                        Log.d("PriorityClassifier", "NB model loaded: $path")
                    }
                    classifier = null
                    modelAvailable = false
                } else {
                    val desiredId = if (useLearned) {
                        val file = if (selected != null) File(selected) else File(appContext.noBackupFilesDir, "models/L1_learned.tflite")
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
            }
        } else {
            modelAvailable = false
        }

        val useLearned = com.brill.zero.settings.AppSettings.getUseLearnedL1(appContext)
        val selected = com.brill.zero.settings.AppSettings.getL1SelectedModelPath(appContext)
        val isJson = useLearned && selected?.lowercase()?.endsWith(".json") == true
        val fusionEnabled = com.brill.zero.settings.AppSettings.getL1FusionEnabled(appContext)
        var wMp = com.brill.zero.settings.AppSettings.getL1FusionWeightMP(appContext)
        var wNb = com.brill.zero.settings.AppSettings.getL1FusionWeightNB(appContext)
        val wSum = (wMp + wNb).coerceAtLeast(1e-6f)
        wMp = wMp / wSum
        wNb = wNb / wSum
        // 若选择了 JSON（NB）模型，按需加载 NB，同时保持 MediaPipe（资产或学习）用于融合
        if (isJson) {
            classifyLock.withLock {
                val path = selected
                if (path != null) {
                    val desiredId = "nb:$path"
                    if (currentModelId != desiredId || nbModel == null) {
                        nbModel = L1NaiveBayes.loadModel(File(path))
                        currentModelId = desiredId
                        Log.d("PriorityClassifier", "NB model loaded: $path")
                    }
                }
                // 确保 MediaPipe 可用（createOptions 会在 JSON 时回退资产模型）
                if (fusionEnabled && (classifier == null || !modelAvailable)) {
                    try {
                        val opts = createOptions()
                        if (opts != null) {
                            classifier = TextClassifier.createFromOptions(appContext, opts)
                            modelAvailable = true
                        }
                    } catch (e: Exception) {
                        Log.e("PriorityClassifier", "MediaPipe init failed for fusion: ${e.message}")
                        modelAvailable = false
                    }
                }
            }
        }

        // 统一预处理文本（供两个模型）
        val aweText = preprocessForAscii(fullText)

        var mpBestName: String? = null
        var mpBestScore: Float = 0f
        var mpDist: FloatArray? = null
        if (useL1MediaPipe && modelAvailable && classifier != null) {
            try {
                val result: TextClassifierResult = classifyLock.withLock { classifier!!.classify(aweText) }
                val cats = result.classificationResult().classifications().firstOrNull()?.categories() ?: emptyList()
                // 将 MediaPipe 输出按标签名聚合到三类分布
                val labelOrder = listOf("高优先级", "中优先级", "低优先级")
                val dist = FloatArray(labelOrder.size)
                for (c in cats) {
                    val name = c.categoryName()
                    val idx = labelOrder.indexOf(name)
                    if (idx >= 0) dist[idx] += c.score()
                }
                // 归一化
                val sum = dist.sum()
                if (sum > 0f) {
                    for (i in dist.indices) dist[i] = dist[i] / sum
                }
                mpDist = dist
                val bestIdx = dist.indices.maxByOrNull { dist[it] } ?: -1
                if (bestIdx >= 0) {
                    mpBestName = labelOrder[bestIdx]
                    mpBestScore = dist[bestIdx]
                }
                Log.d("ZeroL1-MP", "MP分布=${dist.joinToString(",") { "%.2f".format(it) }} best=${mpBestName}(${"%.2f".format(mpBestScore)})")
            } catch (e: Exception) {
                Log.e("PriorityClassifier", "Model classification failed: ${e.message}", e)
                modelAvailable = false
            }
        }

        var nbBestName: String? = null
        var nbBestScore: Double = 0.0
        var nbDist: DoubleArray? = null
        if (nbModel != null) {
            try {
                val p = L1NaiveBayes.proba(nbModel!!, aweText)
                nbDist = p
                val idx = (p.indices.maxByOrNull { p[it] } ?: 0)
                nbBestName = nbModel!!.labels[idx]
                nbBestScore = p[idx]
                Log.d("ZeroL1-NB", "NB分布=${p.joinToString(",") { "%.2f".format(it) }} best=${nbBestName}(${"%.2f".format(nbBestScore)})")
            } catch (e: Exception) {
                Log.e("PriorityClassifier", "NB classification failed: ${e.message}", e)
            }
        }

        val mpThreshold = 0.70f
        val nbThreshold = 0.70
        val labelOrder = listOf("高优先级", "中优先级", "低优先级")

        // 若未开启融合：
        if (!fusionEnabled) {
            if (isJson && nbBestName != null) {
                val mapped = labelMap[nbBestName] ?: Priority.LOW
                Log.d("ZeroL1-Fusion", "融合关闭，使用NB: ${nbBestName}")
                return@withContext mapped
            }
            if (mpBestName != null) {
                val mapped = labelMap[mpBestName] ?: Priority.LOW
                Log.d("ZeroL1-Fusion", "融合关闭，使用MP: ${mpBestName}")
                return@withContext mapped
            }
        } else {
            // 开启融合：优先高置信度，否则加权融合
            if (mpBestName != null && mpBestScore >= mpThreshold) {
                val mapped = labelMap[mpBestName] ?: Priority.LOW
                Log.d("ZeroL1-Fusion", "采用MP: ${mpBestName} (${"%.2f".format(mpBestScore)})")
                return@withContext mapped
            }
            if (nbBestName != null && nbBestScore >= nbThreshold) {
                val mapped = labelMap[nbBestName] ?: Priority.LOW
                Log.d("ZeroL1-Fusion", "采用NB: ${nbBestName} (${"%.2f".format(nbBestScore)})")
                return@withContext mapped
            }
            if ((mpDist != null) || (nbDist != null)) {
                val fused = DoubleArray(labelOrder.size) { 0.0 }
                if (mpDist != null) {
                    for (i in fused.indices) fused[i] += wMp.toDouble() * mpDist[i].toDouble()
                }
                if (nbDist != null) {
                    for (i in fused.indices) fused[i] += wNb.toDouble() * nbDist[i]
                }
                val bestIdx = fused.indices.maxByOrNull { fused[it] } ?: 2
                val bestName = labelOrder[bestIdx]
                val mapped = labelMap[bestName] ?: Priority.LOW
                Log.d("ZeroL1-Fusion", "加权融合(${"%.2f".format(wMp)}:${"%.2f".format(wNb)}): ${bestName} (${"%.2f".format(fused[bestIdx])})")
                return@withContext mapped
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
