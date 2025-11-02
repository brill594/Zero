package com.Brill.zero.ml

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.Brill.zero.domain.model.Priority
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.text.classifier.TextClassifier

/**
 * L1 (守门员) 分类器。
 * 使用 MediaPipe Model Maker 训练的 AverageWordEmbedding 模型。
 * [!] V2 修复: 替换了原始的 Stub，使用 TFLite Task Library 来确保
 * 与 MediaPipe 训练时一致的预处理。
 */
class PriorityClassifier(private val context: Context) {

    // 1. 定义我们的模型和标签
    // [!] 确保这个路径与你放入 assets 的路径一致
    private val modelPath = "models/l1_gatekeeper_model.tflite"

    // [!] 关键: 这些标签必须与你训练时 L1 数据集 (train_gatekeeper.csv) 中的
    // 标签字符串 *完全* 一致 (我们当时清洗掉了括号)
    private val labelMap = mapOf(
        "高优先级" to Priority.HIGH,
        "中优先级" to Priority.MEDIUM,
        "低优先级" to Priority.LOW
    )

    // 2. 初始化 TextClassifier
    // 使用 lazy 确保只在第一次使用时才在后台线程加载
    private val textClassifier: TextClassifier by lazy {
        Log.i("ZeroL1", "正在加载 L1 (Gatekeeper) 模型...")
        val startTime = SystemClock.elapsedRealtime()

        // 设置 TFLite 选项 (例如，使用 NNAPI 代理)
        val options = BaseOptions.builder()
            // .useNnapi() // 可选: 开启 NNAPI 加速
            .setNumThreads(2) // L1 模型很小, 2个线程足够
            .build()

        val classifierOptions = TextClassifier.Options.builder()
            .setBaseOptions(options)
            .build()

        val classifier = TextClassifier.createFromFile(context, modelPath, classifierOptions)

        Log.i("ZeroL1", "L1 模型加载完毕 (耗时: ${SystemClock.elapsedRealtime() - startTime}ms)")
        classifier
    }

    /**
     * 对输入的完整通知文本进行优先级分类。
     * @param fullText 完整的通知内容 (e.g., "老板 · 报告明天发我")
     * @return Priority (HIGH, MEDIUM, or LOW)
     */
    fun predictPriority(fullText: String): Priority {
        val startTime = SystemClock.elapsedRealtime()

        // 1. 运行推理
        // [!] MediaPipe Task Library 会自动处理所有预处理
        val categories: List<Category> = textClassifier.classify(fullText)

        // 2. 解析结果
        val bestCategory = categories.maxByOrNull { it.score }

        if (bestCategory == null) {
            Log.w("ZeroL1", "L1 模型返回了空结果。")
            return Priority.LOW // 默认降级
        }

        // 3. 将字符串标签 (e.g., "高优先级") 映射到我们的 Enum
        val resultPriority = labelMap[bestCategory.label] ?: Priority.LOW

        Log.d("ZeroL1", "L1 预测: '${bestCategory.label}' (Score: %.2f) -> %s (耗时: %dms)".format(
            bestCategory.score,
            resultPriority.name,
            SystemClock.elapsedRealtime() - startTime
        ))

        return resultPriority
    }
}