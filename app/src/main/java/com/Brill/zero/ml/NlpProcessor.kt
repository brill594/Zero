package com.Brill.zero.ml

import android.content.Context
import android.util.Log
import com.Brill.zero.domain.model.Todo
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.text.classifier.TextClassifier

// [!!!] V26 修复: 定义 L2/L3 的处理结果
sealed class L2ProcessResult {
    /** 任务已被 L3-RegEx 立即处理 */
    data class Handled(val todo: Todo) : L2ProcessResult()
    /** 任务需要 L3-SLM 引擎进行后台处理 */
    data class RequiresL3_SLM(val intent: String) : L2ProcessResult()
    /** 任务应被忽略 */
    object Ignore : L2ProcessResult()
}

/**
 * L2 (分流器) + L3-A (RegEx 即时引擎)
 * [!] V26 修复: 注入了 L3_SLM_Processor
 */
class NlpProcessor(context: Context) {

    // L2-Intent (MobileBERT)
    private val modelPath = "models/l2_processor_intent.tflite"
    private val intentClassifier: TextClassifier by lazy { /* ... (同 V25, 已折叠) ... */
        Log.i("ZeroL2", "正在加载 L2 (MobileBERT-Intent) 模型...")
        val options = BaseOptions.builder().setNumThreads(4).build()
        val classifierOptions = TextClassifier.Options.builder().setBaseOptions(options).build()
        TextClassifier.createFromFile(context, modelPath, classifierOptions)
    }

    // [!!!] V26 修复: L3-B (SLM) 引擎
    // 我们在 L2 中持有 L3 的实例，以便在 Worker 中调用
    val l3_slm_processor: L3_SLM_Processor = L3_SLM_Processor(context)

    // L2-Intent 的标签
    private val L3_REGEX_INTENTS = setOf("验证码", "未接来电", "财务变动", "物流信息")
    // L3-SLM (Qwen) 将处理这些
    private val L3_SLM_INTENTS = setOf("工作沟通", "日程提醒", "社交闲聊")
    // L3 将忽略这些
    private val L3_IGNORE_INTENTS = setOf("系统通知")


    /**
     * 核心处理函数 (L2 分流)。
     * @return L2ProcessResult, 告诉调用者下一步该做什么。
     */
    fun processNotification(fullText: String): L2ProcessResult {
        // L2-Intent: 运行 MobileBERT 分类
        val categories: List<Category> = intentClassifier.classify(fullText)
        val bestCategory = categories.maxByOrNull { it.score }

        if (bestCategory == null) {
            Log.w("ZeroL2", "L2-Intent 模型返回了空结果。")
            return L2ProcessResult.Ignore
        }

        val intent = bestCategory.label
        val score = bestCategory.score

        Log.i("ZeroL2", "L2 预测: '$intent' (Score: %.2f)".format(score))

        // L3-A (RegEx): 检查 L2 意图是否可以被 RegEx 立即处理
        if (intent in L3_REGEX_INTENTS) {
            val todo = runL3RegExEngine(fullText, intent)
            // 即使 RegEx 失败了 (null), 也不要交给 SLM, 直接忽略
            return todo?.let { L2ProcessResult.Handled(it) } ?: L2ProcessResult.Ignore
        }

        // L3-B (SLM): 检查是否需要 Qwen
        if (intent in L3_SLM_INTENTS) {
            Log.i("ZeroL2", "意图 '$intent' 需要 L3-SLM 引擎处理。")
            return L2ProcessResult.RequiresL3_SLM(intent)
        }

        // 默认忽略 (e.g., "系统通知")
        return L2ProcessResult.Ignore
    }

    /** L3-A (RegEx 引擎) ... (同 V25, 已折叠) ... */
    private fun runL3RegExEngine(text: String, intent: String): Todo? {
        return when (intent) {
            "验证码" -> {
                val regex = Regex("""(?!.*\b尾号\b.*)(\b[A-Z0-9]{4,8}\b)""")
                val match = regex.find(text)
                if (match != null) Todo(title = "验证码: ${match.groupValues[1]}", dueAt = null) else null
            }
            "未接来电" -> {
                val regex = Regex("""(来自|未接来电：)\s*'?([^'\(\)]+)'?""")
                val match = regex.find(text)
                if (match != null) Todo(title = "回拨 ${match.groupValues[2].trim()}", dueAt = null)
                else Todo(title = "回拨未接来电", dueAt = null)
            }
            "财务变动" -> {
                val regex = Regex("""(收入|支出|消费|到账|转入|转出)\s*([\d,]+\.\d{2})\s*元""")
                val match = regex.find(text)
                if (match != null) Todo(title = "${match.groupValues[1]} ${match.groupValues[2]} 元", dueAt = null) else null
            }
            "物流信息" -> {
                val regex = Regex("""(取件码|取餐码)[:：\s]*(\b[A-Z0-9\-]+\b)""")
                val match = regex.find(text)
                if (match != null) Todo(title = "取快递/外卖 (取件码: ${match.groupValues[2]})", dueAt = null)
                else if (text.contains("已放入")) Todo(title = "取快递 (已放入自提柜)", dueAt = null)
                else null
            }
            else -> null
        }
    }
}