package com.brill.zero.ml

import android.content.Context
import android.util.Log
import com.brill.zero.domain.model.Todo
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.mediapipe.tasks.text.textclassifier.TextClassifierResult

// 结果类型（命名去下划线）
sealed class L2ProcessResult {
    data class Handled(val todo: Todo) : L2ProcessResult()
    data class RequiresL3Slm(val intent: String) : L2ProcessResult()
    object Ignore : L2ProcessResult()
}

/**
 * L2 (分流器) + L3-A (RegEx 即时引擎) —— MediaPipe Tasks-Text 版本
 */
class NlpProcessor(context: Context) {

    private val modelPath = "models/l2_processor_intent.tflite"

    // L3-B (SLM)
    val l3SlmProcessor: L3_SLM_Processor = L3_SLM_Processor(context)

    // 意图集合
    private val L3_REGEX_INTENTS = setOf("验证码", "未接来电")
    private val L3_SLM_INTENTS   = setOf("工作沟通", "日程提醒", "社交闲聊", "财务变动", "物流信息")

    // L2 模型加载（MediaPipe）
    private val intentClassifier: TextClassifier by lazy {
        Log.i("ZeroL2-MP", "正在加载 L2 (MobileBERT) MediaPipe 模型...")

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelPath)
            // .setNumThreads(4) // 个别版本无该方法，先省略保持兼容
            .build()

        val options = TextClassifier.TextClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .build()

        TextClassifier.createFromOptions(context, options)
    }

    /** 核心分流 */
    fun processNotification(fullText: String): L2ProcessResult {
        val result: TextClassifierResult = intentClassifier.classify(fullText)

        // 先取 classificationResult() 再取 classifications → categories
        val bestCategory = result.classificationResult()
            .classifications().firstOrNull()
            ?.categories()?.maxByOrNull { it.score() }

        if (bestCategory == null) {
            Log.w("ZeroL2-MP", "L2-Intent MediaPipe 模型返回空结果")
            return L2ProcessResult.Ignore
        }

        val intent = bestCategory.categoryName()
        val score  = bestCategory.score()
        Log.i("ZeroL2-MP", "L2 预测: '$intent' (score=%.2f)".format(score))

        // L3-A：可正则立即处理
        if (intent in L3_REGEX_INTENTS) {
            val todo = runL3RegExEngine(fullText, intent)
            return todo?.let { L2ProcessResult.Handled(it) } ?: L2ProcessResult.Ignore
        }

        // L3-B：需要小模型 SLM 批处理
        if (intent in L3_SLM_INTENTS) {
            return L2ProcessResult.RequiresL3Slm(intent)
        }

        return L2ProcessResult.Ignore
    }

    /** L3-A：正则即时引擎 */
    private fun runL3RegExEngine(text: String, intent: String): Todo? = when (intent) {
        "验证码" -> {
            val regex = Regex("""(?!.*\b尾号\b.*)(\b[A-Z0-9]{4,8}\b)""")
            val m = regex.find(text)
            if (m != null) Todo(title = "验证码: ${m.groupValues[1]}", dueAt = null) else null
        }
        "未接来电" -> {
            // 在字符类里 () 无需转义：[^'()]
            val regex = Regex("""(来自|未接来电：)\s*'?([^'()]+)'?""")
            val m = regex.find(text)
            if (m != null) Todo(title = "回拨 ${m.groupValues[2].trim()}", dueAt = null)
            else Todo(title = "回拨未接来电", dueAt = null)
        }
        "财务变动" -> {
            val regex = Regex("""(收入|支出|消费|到账|转入|转出)\s*([\d,]+\.\d{2})\s*元""")
            val m = regex.find(text)
            if (m != null) Todo(title = "${m.groupValues[1]} ${m.groupValues[2]} 元", dueAt = null) else null
        }
        "物流信息" -> {
            val regex = Regex("""(取件码|取餐码)[:：\s]*(\b[A-Z0-9\-]+\b)""")
            val m = regex.find(text)
            when {
                m != null -> Todo(title = "取快递/外卖 (取件码: ${m.groupValues[2]})", dueAt = null)
                text.contains("已放入") -> Todo(title = "取快递 (已放入自提柜)", dueAt = null)
                else -> null
            }
        }
        else -> null
    }
    // 仅调试用：直接跑 L2 分类，返回 Top-1 (label, score)
    fun debugTopIntent(text: String): Pair<String, Float>? {
        val r = intentClassifier.classify(text)
            .classificationResult()
            .classifications()
            .firstOrNull()
            ?.categories()
            ?.maxByOrNull { it.score() }
        return r?.let { it.categoryName() to it.score() }
    }

    // 暴露 L3-SLM 可选意图（供下拉选择）
    fun debugL3Intents(): List<String> = listOf("工作沟通", "日程提醒", "社交闲聊")

}
