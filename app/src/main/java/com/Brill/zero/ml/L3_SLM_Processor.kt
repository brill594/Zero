package com.brill.zero.ml

import android.content.Context
import android.util.Log
import com.brill.zero.domain.model.Todo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.*
import java.util.regex.Pattern

/**
 * L3-B (调度引擎 - SLM)
 * - 若已集成 llama.cpp JNI（com.Brill.zero.llama.Llama），优先调用 JNI 做真推理
 * - 若尚未集成，则 fallback 为 Stub（保持当前可运行）
 */
class L3_SLM_Processor(private val context: Context) {
    private val grammarAssetPath = "Grammar/json_ie.gbnf"

    // 懒加载并缓存 GBNF 文本；读取失败返回 null（会自动 fallback）
    private val grammarText: String? by lazy {
        runCatching {
            context.assets.open(grammarAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                .trim() // 去掉文件首尾空白/换行，避免意外解析问题
        }.getOrNull()
    }
    // —— 强化版 System Prompt：只输出一个纯 JSON 对象 ——
    private val SYSTEM_PROMPT = """
<|im_start|>system
你是一个AI助手。请从通知中提取'意图','摘要','截止时间'，并以JSON格式返回。

【JSON 键名】
键必须为 "intent", "summary", "due_time"。

【任务 1: 'intent' (意图)】
值【必须】从以下 8 个字符串中精确选择一个：
["财务变动", "工作沟通", "物流信息", "验证码", "系统通知", "未接来电", "日程提醒", "社交闲聊"]
* 意图判断【必须】基于通知的【正文内容】，而不是【前缀】。

【任务 2: 'summary' (摘要)】
* 【规则】: 摘要必须是一个【简洁的行动项】，长度在 5 到 15 个字之间。
* 【规则】: 摘要应以一个“动作”(动词)开头 (例如: "取...", "回拨...", "给...")。
* 【严禁】: 严禁将原始 'text' 不经修改地复制到 'summary' 中。
* 示例 (工作): "给老板发PRD"
* 示例 (财务): "工资到账 22,500.00元"
* 示例 (验证码): "抖音验证码: 123456"

【任务 3: 'due_time' (截止时间)】
* 【必须】从 'text' 中提取时间。
* [!!!] 【V13 规则】: 如果文本中包含多个时间 (如 "原定明天，改到今天下午"), 【必须】优先提取【最终被确认】的时间 (即 "今天下午")。
* 如果 'text' 中【没有】时间，'due_time' 必须为 null。
<|im_end|>
<|im_start|>user
{USER_NOTIFICATION_TEXT_HERE}<|im_end|>
<|im_start|>assistant
    """.trimIndent()

    // GGUF 模型相对 assets 路径（随 APK 的“种子模型”）
    private val assetModelPath = "models/l3_processor_model.Q5_K_M.gguf"

    @Volatile
    private var threadsOverride: Int? = null
    @Volatile
    private var gpuLayersOverride: Int? = null

    /**
     * 允许从 UI 覆盖 SLM 的线程数（1..availableProcessors，上限按 8 取）
     */
    fun setThreadsOverride(value: Int?) {
        threadsOverride = value?.coerceAtLeast(1)?.coerceAtMost(Runtime.getRuntime().availableProcessors())
    }

    /**
     * 允许从 UI 覆盖 GPU 卸载层数（0 表示禁用卸载；建议在 0..32 之间）
     */
    fun setGpuLayersOverride(value: Int?) {
        gpuLayersOverride = value?.coerceAtLeast(0)?.coerceAtMost(64)
    }

    // ————————————————————————————————————————————————————————————
    // 1) 推理：JNI（若可用）→ 否则 Stub
    // ————————————————————————————————————————————————————————————
    private fun ensureLocalModelPath(): String {
        // 优先使用 noBackupDir，若无则从 assets 复制过去
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        val dst = File(dir, File(assetModelPath).name)
        if (!dst.exists()) {
            Log.d("ZeroL3-SLM", "Copying L3 model from assets: $assetModelPath")
            try {
                context.assets.open(assetModelPath).use { `in` ->
                    FileOutputStream(dst).use { out ->
                        `in`.copyTo(out)
                    }
                }
                Log.d("ZeroL3-SLM", "L3 model copied successfully to: ${dst.absolutePath}")
            } catch (e: Exception) {
                Log.e("ZeroL3-SLM", "Failed to copy L3 model: ${e.message}", e)
                throw e
            }
        } else {
            Log.d("ZeroL3-SLM", "L3 model already exists at: ${dst.absolutePath}")
        }
        return dst.absolutePath
    }

    private fun buildChatMLPrompt(userText: String): String =
        "<|im_start|>system\n$SYSTEM_PROMPT<|im_end|>\n" +
                "<|im_start|>user\n$userText<|im_end|>\n" +
                "<|im_start|>assistant\n"

    private fun tryRunViaJNI(prompt: String, maxNewTokens: Int = 48): String? {
        return try {
            // 通过反射调用：com.Brill.zero.llama.Llama（若未集成会抛异常→ fallback）
            val clazz = Class.forName("com.brill.zero.llama.Llama")
            val mInit = clazz.getMethod(
                "nativeInit",
                String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            val mCompletion = clazz.getMethod(
                "nativeCompletion",
                java.lang.Long.TYPE, String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            val mFree = clazz.getMethod("nativeFree", java.lang.Long.TYPE)

            val modelPath = ensureLocalModelPath()
            // 默认线程改为 1；UI 可通过 setThreadsOverride 覆盖
            val defaultThreads = 1
            val threads = (threadsOverride ?: defaultThreads).coerceAtMost(8)
            // 缩小上下文窗口以提升推理速度（提示词约 200 tokens 足够）
            // Vulkan/其他 GPU 后端可用时，传递非零 nGpuLayers 以尽可能将层卸载到 GPU
            // 默认保守：0（CPU-only）；UI 可通过 setGpuLayersOverride(value) 打开 GPU 卸载
            val nGpuLayers = (gpuLayersOverride ?: 0)
            val handle = mInit.invoke(null, modelPath, 2048, nGpuLayers, threads) as Long
            // 启用 GBNF 语法：强约束仅输出一个 JSON 对象
            val g = grammarText
            Log.d("ZeroL3-SLM", "JNI 调用：启用语法=${g != null}, maxNewTokens=$maxNewTokens, threads=$threads (override=${threadsOverride}), nGpuLayers=$nGpuLayers (override=${gpuLayersOverride})")
            val out = mCompletion.invoke(
                null,
                handle,
                prompt,
                g,                           // ← 传入语法文本；为 null 时原生自动关闭语法采样
                maxNewTokens,
                0f,                          // temperature（0 表示贪婪）
                1.0f,                        // top-p（1 表示禁用）
                42
            ) as String

            mFree.invoke(null, handle)
            val cleaned = out.trim()
            if (cleaned.isEmpty()) {
                Log.w("ZeroL3-SLM", "JNI 返回空字符串")
                null
            } else {
                Log.d("ZeroL3-SLM", "JNI 输出片段='${cleaned.take(160)}'")
                cleaned
            }
        } catch (t: Throwable) {
            Log.w("ZeroL3-SLM", "JNI 不可用，使用 Stub：${t.message}")
            null
        }
    }

    // 为避免 nativeCompletion 长时间阻塞，增加软超时包装。
    private fun tryRunViaJNIWithTimeout(prompt: String, timeoutMs: Long = 900000): String? {
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                // 提升推理线程优先级，帮助系统更倾向在大核调度
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                } catch (t: Throwable) {
                    android.util.Log.w("ZeroL3-SLM", "设置线程优先级失败: ${t.message}")
                }
                r.run()
            }, "L3JNI").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }
        return try {
            val future = exec.submit<String?> {
                // 使用较小的生成长度以加速返回；配合 native 看到 '}' 即提前停止
                tryRunViaJNI(prompt, maxNewTokens = 48)
            }
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            android.util.Log.w("ZeroL3-SLM", "JNI 推理超时 ${timeoutMs}ms，使用 Stub")
            null
        } catch (t: Throwable) {
            android.util.Log.w("ZeroL3-SLM", "JNI 调用异常：${t.message}")
            null
        } finally {
            exec.shutdownNow()
        }
    }

    /**
     * 对单条非结构化通知运行 L3-SLM 推理。
     * @param text 完整通知文本
     * @param l2_intent L2 的预测意图（用于一致性校验）
     */
    fun process(text: String, l2_intent: String): Todo? {
        Log.i("ZeroL3-SLM", "L3-B 调用：intent='$l2_intent'")
        val finalPrompt = buildChatMLPrompt(text)

        // A) JNI 真推理（如可用，带软超时）
        val rawOutput = tryRunViaJNIWithTimeout(finalPrompt)

        // 解析 JSON（若失败则回退 Stub）
        return try {
            val cleanJson = rawOutput?.let { extractJson(it) } ?: run {
                if (rawOutput == null) {
                    Log.w("ZeroL3-SLM", "JNI 返回为空或失败，使用 Stub")
                } else {
                    Log.w(
                        "ZeroL3-SLM",
                        "L3 输出非 JSON，使用 Stub；原始片段='${rawOutput.take(120)}'"
                    )
                }
                // 使用 JSONObject.quote 可靠转义，避免 summary 中出现双引号导致 JSON 解析失败
                val fallbackJson = """
                {"intent":${JSONObject.quote(l2_intent)},"summary":${JSONObject.quote(guessSummary(text))},"due_time":${guessDeadlineText(text)}}
                """.trimIndent()
                fallbackJson
            }
            val obj = JSONObject(cleanJson)

            val l3Intent = obj.optString("intent", l2_intent)
            if (l3Intent != l2_intent) {
                Log.w("ZeroL3-SLM", "L3 意图($l3Intent) ≠ L2($l2_intent)，先信 L2")
            }

            val summary = obj.optString("summary", guessSummary(text))
            val deadlineText = obj.opt("due_time")?.toString()?.takeIf { it != "null" }
            val dueAt = parseDeadlineToEpochMillis(deadlineText)

            Log.i("ZeroL3-SLM", "L3 结果：summary='$summary' due_time='$deadlineText' → $dueAt")
            Todo(title = summary, dueAt = dueAt)
        } catch (e: Exception) {
            Log.e("ZeroL3-SLM", "JSON 解析失败: ${e.message}")
            null
        }
    }

    // —— 解析器：从模型输出或原文中提取 JSON —— //
    private fun extractJson(rawOutput: String): String? {
        // 1) ```json ... ``` 包裹
        val jsonPattern = Pattern.compile("```json\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL)
        var m = jsonPattern.matcher(rawOutput)
        if (m.find()) return m.group(1)

        // 2) 直接首尾大括号
        val bracePattern = Pattern.compile("(\\{[\\s\\S]*\\})")
        m = bracePattern.matcher(rawOutput)
        if (m.find()) return m.group(1)

        // 3) 已经是纯 JSON
        return rawOutput.trim().takeIf { it.startsWith("{") && it.endsWith("}") }
    }

    // —— 没有 SLM 时的保底摘要 —— //
    private fun guessSummary(text: String): String {
        val t = text.replace("\\s+".toRegex(), " ").trim()
        // 挑一个以动词开头的 5~15 字“行动项”
        val verbs = listOf("处理", "确认", "回复", "回拨", "跟进", "查看", "领取", "取件", "付款", "报备", "提交", "催办")
        val v = verbs.firstOrNull { t.contains(it) } ?: "处理"
        val core = t.take(28).removePrefix("【").removePrefix("通知").removePrefix("消息")
        val s = if (core.startsWith(v)) core else "$v $core"
        return s.take(60)
    }

    private fun guessDeadlineText(text: String): String {
        // 简单猜一个展示用文本（给 fallback JSON 用）
        val hasTomorrow = Regex("明天|tomorrow").containsMatchIn(text)
        val hasToday = Regex("今天|today").containsMatchIn(text)
        val time = Regex("(\\d{1,2})(:|：|点)(\\d{1,2})?").find(text)?.value ?: ""
        return when {
            hasToday && time.isNotEmpty()   -> "\"今天$time\""
            hasTomorrow && time.isNotEmpty() -> "\"明天$time\""
            hasTomorrow -> "\"明天\""
            else -> "null"
        }
    }

    // ————————————————————————————————————————————————————————————
    // 2) TODO 完成：中文自然时间解析 → EpochMillis
    //    规则：若文本中出现多个候选时间，采用“最后出现”的那个
    // ————————————————————————————————————————————————————————————
    private fun parseDeadlineToEpochMillis(deadline: String?): Long? {
        if (deadline.isNullOrBlank()) return null
        val text = deadline.replace("（", "(").replace("）", ")").replace("：", ":").trim()

        val now = ZonedDateTime.now()
        val zone = now.zone

        data class Hit(val index: Int, val zdt: ZonedDateTime)

        val hits = mutableListOf<Hit>()

        // 0) 绝对日期: 11月4日 / 11/4 / 11-4 [可带时刻]
        fun tryMonthDayWithTime(s: String) {
            val m1 = Regex("(\\d{1,2})月(\\d{1,2})日(?:\\s*(上午|早上|中午|下午|晚上|晚|傍晚)?)?\\s*(\\d{1,2})(?::|：|点)(\\d{1,2})?(?:分|\\b)?").findAll(s)
            for (mm in m1) {
                val (M, D, ap, H, m) = listOf(
                    mm.groupValues.getOrNull(1),
                    mm.groupValues.getOrNull(2),
                    mm.groupValues.getOrNull(3),
                    mm.groupValues.getOrNull(4),
                    mm.groupValues.getOrNull(5)
                )
                val t = localDateTimeFor(M!!.toInt(), D!!.toInt(), H!!.toInt(), m?.toIntOrNull(), ap)
                val z = t.atZone(zone).let { rollYearIfPast(it, now) }
                hits += Hit(mm.range.first, z)
            }
            val m2 = Regex("(\\d{1,2})月(\\d{1,2})日").findAll(s) // 仅日期，无时刻 → 默认 18:00
            for (mm in m2) {
                val M = mm.groupValues[1].toInt()
                val D = mm.groupValues[2].toInt()
                val z = LocalDateTime.of(now.year, M, D, 18, 0)
                    .atZone(zone).let { rollYearIfPast(it, now) }
                hits += Hit(mm.range.first, z)
            }
            val m3 = Regex("\\b(\\d{1,2})[/-](\\d{1,2})\\b(?:\\s*(\\d{1,2}):(\\d{1,2}))?").findAll(s)
            for (mm in m3) {
                val M = mm.groupValues[1].toInt()
                val D = mm.groupValues[2].toInt()
                val HH = mm.groupValues.getOrNull(3)?.toIntOrNull() ?: 18
                val mmn = mm.groupValues.getOrNull(4)?.toIntOrNull() ?: 0
                val z = LocalDateTime.of(now.year, M, D, HH, mmn)
                    .atZone(zone).let { rollYearIfPast(it, now) }
                hits += Hit(mm.range.first, z)
            }
        }

        // 1) 相对日期：今天/明天/后天 [+ 时段/时刻]
        fun tryRelativeDay(s: String) {
            val pat = Regex("(今天|明天|后天)(?:\\s*(上午|早上|中午|下午|晚上|晚|傍晚)?)?(?:\\s*(\\d{1,2})(?:点|:|：)(\\d{1,2})?(?:分)?)?")
            pat.findAll(s).forEach { mm ->
                val day = mm.groupValues[1]
                val ap = mm.groupValues.getOrNull(2)
                val H = mm.groupValues.getOrNull(3)?.toIntOrNull()
                val M = mm.groupValues.getOrNull(4)?.toIntOrNull()
                val base = when (day) {
                    "今天" -> now
                    "明天" -> now.plusDays(1)
                    else   -> now.plusDays(2)
                }.toLocalDate()
                val t = pickTime(H, M, ap)
                val z = LocalDateTime.of(base, t).atZone(zone)
                hits += Hit(mm.range.first, z)
            }
            // 今晚/明早/明晚
            Regex("(今晚|明早|明晚|今早)").findAll(s).forEach { mm ->
                val (ap, addDays) = when (mm.groupValues[1]) {
                    "今早" -> Pair("上午", 0)
                    "今晚" -> Pair("晚上", 0)
                    "明早" -> Pair("上午", 1)
                    else   -> Pair("晚上", 1) // 明晚
                }
                val base = now.toLocalDate().plusDays(addDays.toLong())
                val t = pickTime(null, null, ap)
                val z = LocalDateTime.of(base, t).atZone(zone)
                hits += Hit(mm.range.first, z)
            }
        }

        // 2) 周几 / 本周X / 下周X / 周末
        fun tryWeekday(s: String) {
            val wpat = Regex("(本周|下周)?\\s*(周|星期)(一|二|三|四|五|六|日|天)(?:\\s*(上午|早上|中午|下午|晚上|晚|傍晚)?)?(?:\\s*(\\d{1,2})(?:点|:|：)(\\d{1,2})?)?")
            wpat.findAll(s).forEach { mm ->
                val scope = mm.groupValues.getOrNull(1) // 本周/下周/空
                val dayZh = mm.groupValues[3]
                val ap = mm.groupValues.getOrNull(4)
                val H = mm.groupValues.getOrNull(5)?.toIntOrNull()
                val M = mm.groupValues.getOrNull(6)?.toIntOrNull()

                val targetDow = zhWeekdayToIso(dayZh) // 1..7
                var base = now
                if (scope == "下周") base = base.plusWeeks(1)
                // 计算该周的周一
                val startOfWeek = base.with(java.time.DayOfWeek.MONDAY)
                var target = startOfWeek.plusDays((targetDow - 1).toLong())
                // 若 scope 为空且当天已过该 DOW，则指向“下一次出现”的该周几
                if (scope.isNullOrBlank() && !target.isAfter(now)) {
                    target = target.plusWeeks(1)
                }
                val t = pickTime(H, M, ap)
                hits += Hit(mm.range.first, LocalDateTime.of(target.toLocalDate(), t).atZone(zone))
            }

            Regex("周末").findAll(s).forEach { mm ->
                // 取最近的周六 10:00
                var sat = now.with(java.time.DayOfWeek.SATURDAY)
                if (!sat.isAfter(now)) sat = sat.plusWeeks(1)
                hits += Hit(mm.range.first, LocalDateTime.of(sat.toLocalDate(), LocalTime.of(10, 0)).atZone(zone))
            }
        }

        // 3) 仅时刻（无日期）：14:30 / 2点半 / 9点
        fun tryTimeOnly(s: String) {
            val t1 = Regex("\\b(\\d{1,2}):(\\d{1,2})\\b").findAll(s)
            for (mm in t1) {
                val H = mm.groupValues[1].toInt()
                val M = mm.groupValues[2].toInt()
                var z = now.withHour(H).withMinute(M).withSecond(0).withNano(0)
                if (!z.isAfter(now)) z = z.plusDays(1)
                hits += Hit(mm.range.first, z)
            }
            val t2 = Regex("(上午|早上|中午|下午|晚上|晚|傍晚)?\\s*(\\d{1,2})点(半|(\\d{1,2})分)?").findAll(s)
            for (mm in t2) {
                val ap = mm.groupValues.getOrNull(1)
                val H = mm.groupValues[2].toInt()
                val hasHalf = mm.groupValues.getOrNull(3) == "半"
                val M = if (hasHalf) 30 else mm.groupValues.getOrNull(4)?.toIntOrNull()
                val t = pickTime(H, M, ap)
                var z = now.withHour(t.hour).withMinute(t.minute).withSecond(0).withNano(0)
                if (!z.isAfter(now)) z = z.plusDays(1)
                hits += Hit(mm.range.first, z)
            }
        }

        // 执行各解析器
        tryMonthDayWithTime(text)
        tryRelativeDay(text)
        tryWeekday(text)
        tryTimeOnly(text)

        if (hits.isEmpty()) return null

        // 若出现多个候选 → 采用“最后出现”的（index 最大的）
        val best = hits.maxBy { it.index }
        return best.zdt.toInstant().toEpochMilli()
    }

    // ———— 工具函数 ————
    private fun rollYearIfPast(z: ZonedDateTime, now: ZonedDateTime): ZonedDateTime =
        if (z.isBefore(now)) z.plusYears(1) else z

    private fun localDateTimeFor(month: Int, day: Int, hour: Int, minute: Int?, ap: String?): LocalDateTime {
        val hm = adjustByAp(hour, minute ?: 0, ap)
        val year = Year.now().value
        return LocalDateTime.of(year, month, day, hm.first, hm.second)
    }

    private fun pickTime(H: Int?, M: Int?, ap: String?): LocalTime {
        // 若缺时刻，按时段给默认：上午=09:00，中午=12:00，下午=15:00，晚上/晚/傍晚=21:00；否则 18:00
        return if (H == null && M == null) {
            when (ap) {
                "上午", "早上" -> LocalTime.of(9, 0)
                "中午" -> LocalTime.of(12, 0)
                "下午" -> LocalTime.of(15, 0)
                "晚上", "晚", "傍晚" -> LocalTime.of(21, 0)
                else -> LocalTime.of(18, 0)
            }
        } else {
            val (hh, mm) = adjustByAp(H ?: 0, M ?: 0, ap)
            LocalTime.of(hh, mm)
        }
    }

    private fun adjustByAp(hour: Int, minute: Int, ap: String?): Pair<Int, Int> {
        var h = hour
        var m = minute
        if (ap == "下午" || ap == "晚上" || ap == "晚" || ap == "傍晚") {
            if (h in 1..11) h += 12
        } else if (ap == "中午" && h in 1..11) {
            // “中午3点”→15；“中午12点”保持 12
            if (h != 12) h += 12
        }
        if (h == 24) h = 0
        return h.coerceIn(0, 23) to m.coerceIn(0, 59)
    }

    private fun zhWeekdayToIso(ch: String): Int = when (ch) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; else -> 7 // 日/天
    }
}
