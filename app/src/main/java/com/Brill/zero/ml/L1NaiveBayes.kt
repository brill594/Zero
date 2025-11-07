package com.brill.zero.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import kotlin.math.ln

/**
 * 简易多项式朴素贝叶斯文本分类（端侧训练）
 * - 特征：对预处理文本做分词（空格与单引号），使用哈希桶（默认 32768）统计词频
 * - 标签：高/中/低优先级（与 CSV 的 label 字段一致）
 * - 导出：model_nb.json（含 logPrior 与各类 logProb 向量）+ metrics.json（accuracy）
 */
object L1NaiveBayes {
    private const val TAG = "L1NaiveBayes"

    data class Model(
        val vocabSize: Int,
        val alpha: Float,
        val labels: List<String>,
        val logPrior: FloatArray,
        val logProb: Array<FloatArray>
    )

    private val labelOrder = listOf("高优先级", "中优先级", "低优先级")

    private fun parseCsvTwoCols(line: String): Pair<String, String>? {
        var i = 0
        val n = line.length
        fun readField(): String {
            if (i >= n) return ""
            if (line[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < n) {
                    val c = line[i]
                    if (c == '"') {
                        i++
                        if (i < n && line[i] == '"') { sb.append('"'); i++ } else break
                    } else { sb.append(c); i++ }
                }
                if (i < n && line[i] == ',') i++
                return sb.toString()
            } else {
                val start = i
                while (i < n && line[i] != ',') i++
                val field = line.substring(start, i)
                if (i < n && line[i] == ',') i++
                return field
            }
        }
        val text = readField()
        val label = readField()
        if (text.isEmpty() && label.isEmpty()) return null
        return text to label
    }

    private fun tokenize(ascii: String): List<String> {
        // 训练 CSV 已做拼音或 ASCII 归一化；分词使用空格与单引号
        return ascii.split(Regex("[\u0020']+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(512) // 轻量约束，避免异常长文本影响训练
    }

    private fun hash(token: String, vocabSize: Int): Int {
        // Java/Kotlin 的 hashCode 可能为负数；转正并模 vocab
        val h = token.hashCode()
        val idx = (h and 0x7fffffff) % vocabSize
        return idx
    }

    fun train(
        trainCsv: File,
        vocabSize: Int = 32768,
        alpha: Float = 1.0f
    ): Model {
        val classTokenCounts = Array(labelOrder.size) { IntArray(vocabSize) }
        val classTotalTokens = IntArray(labelOrder.size)
        val classDocCount = IntArray(labelOrder.size)
        var totalDocs = 0

        trainCsv.bufferedReader().use { br ->
            var first = true
            br.forEachLine { raw ->
                if (first) { first = false; return@forEachLine } // 跳过表头
                val pair = parseCsvTwoCols(raw) ?: return@forEachLine
                val (text, labelRaw) = pair
                val label = if (labelRaw == "低优先级/垃圾") "低优先级" else labelRaw
                val cls = labelOrder.indexOf(label)
                if (cls < 0) return@forEachLine
                val toks = tokenize(text)
                toks.forEach { t ->
                    val idx = hash(t, vocabSize)
                    classTokenCounts[cls][idx]++
                    classTotalTokens[cls]++
                }
                classDocCount[cls]++
                totalDocs++
            }
        }

        // 先验：按类文档数
        val logPrior = FloatArray(labelOrder.size) { c ->
            val p = (classDocCount[c].toDouble() + 1.0) / (totalDocs.toDouble() + labelOrder.size)
            ln(p).toFloat()
        }

        // 条件概率：对每类做拉普拉斯平滑
        val logProb = Array(labelOrder.size) { FloatArray(vocabSize) }
        for (c in 0 until labelOrder.size) {
            val denom = classTotalTokens[c].toDouble() + alpha * vocabSize
            val arr = classTokenCounts[c]
            for (i in 0 until vocabSize) {
                val num = arr[i].toDouble() + alpha
                logProb[c][i] = ln(num / denom).toFloat()
            }
        }

        return Model(vocabSize, alpha, labelOrder, logPrior, logProb)
    }

    fun evaluate(model: Model, testCsv: File): Double {
        var first = true
        var correct = 0
        var total = 0
        testCsv.bufferedReader().use { br ->
            br.forEachLine { raw ->
                if (first) { first = false; return@forEachLine }
                val pair = parseCsvTwoCols(raw) ?: return@forEachLine
                val (text, labelRaw) = pair
                val label = if (labelRaw == "低优先级/垃圾") "低优先级" else labelRaw
                val y = labelOrder.indexOf(label)
                if (y < 0) return@forEachLine
                val pred = predict(model, text)
                if (pred == y) correct++
                total++
            }
        }
        return if (total > 0) correct.toDouble() / total else 0.0
    }

    fun predict(model: Model, asciiText: String): Int {
        val toks = tokenize(asciiText)
        val scores = DoubleArray(model.labels.size) { i -> model.logPrior[i].toDouble() }
        toks.forEach { t ->
            val idx = hash(t, model.vocabSize)
            for (c in scores.indices) {
                scores[c] += model.logProb[c][idx].toDouble()
            }
        }
        var bestIdx = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in scores.indices) {
            if (scores[i] > bestScore) { bestScore = scores[i]; bestIdx = i }
        }
        return bestIdx
    }

    fun scores(model: Model, asciiText: String): DoubleArray {
        val toks = tokenize(asciiText)
        val scores = DoubleArray(model.labels.size) { i -> model.logPrior[i].toDouble() }
        toks.forEach { t ->
            val idx = hash(t, model.vocabSize)
            for (c in scores.indices) {
                scores[c] += model.logProb[c][idx].toDouble()
            }
        }
        return scores
    }

    fun proba(model: Model, asciiText: String): DoubleArray {
        val s = scores(model, asciiText)
        // 稳定 softmax：减去最大值
        val max = s.maxOrNull() ?: 0.0
        var sum = 0.0
        val exps = DoubleArray(s.size)
        for (i in s.indices) {
            val e = kotlin.math.exp(s[i] - max)
            exps[i] = e
            sum += e
        }
        if (sum <= 0.0) return DoubleArray(s.size) { 0.0 }
        for (i in exps.indices) exps[i] /= sum
        return exps
    }

    fun predictWithConfidence(model: Model, asciiText: String): Pair<Int, Double> {
        val p = proba(model, asciiText)
        var bestIdx = 0
        var best = -1.0
        for (i in p.indices) {
            if (p[i] > best) { best = p[i]; bestIdx = i }
        }
        return bestIdx to best
    }

    fun export(model: Model, exportDir: File, accuracy: Double) {
        exportDir.mkdirs()
        // 写模型
        val modelFile = File(exportDir, "model_nb.json")
        val obj = JSONObject().apply {
            put("vocab_size", model.vocabSize)
            put("alpha", model.alpha)
            put("labels", model.labels)
            put("log_prior", model.logPrior.map { it.toDouble() })
            // logProb 是二维数组；转为 List<List<Double>> 避免 org.json 的嵌套问题
            val probs = ArrayList<List<Double>>(model.labels.size)
            for (c in model.labels.indices) {
                probs.add(model.logProb[c].map { it.toDouble() })
            }
            put("log_prob", probs)
        }
        FileWriter(modelFile).use { it.write(obj.toString()) }

        // 写指标
        val metrics = JSONObject().apply {
            put("accuracy", accuracy)
            put("samples", 0) // 简化：如需可扩展计数
        }
        FileWriter(File(exportDir, "metrics.json")).use { it.write(metrics.toString()) }

        Log.i(TAG, "Exported NB model to ${modelFile.absolutePath} (acc=${"%.3f".format(accuracy)})")
    }

    fun loadModel(file: File): Model? = runCatching {
        val txt = file.readText()
        val obj = JSONObject(txt)
        val vocab = obj.getInt("vocab_size")
        val alpha = obj.getDouble("alpha").toFloat()
        val labels = obj.getJSONArray("labels").let { arr ->
            List(arr.length()) { i -> arr.getString(i) }
        }
        val logPrior = obj.getJSONArray("log_prior").let { arr ->
            FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        }
        val probsArr = obj.getJSONArray("log_prob")
        val logProb = Array(labels.size) { FloatArray(vocab) }
        for (c in 0 until labels.size) {
            val row = probsArr.getJSONArray(c)
            for (i in 0 until vocab) {
                logProb[c][i] = row.getDouble(i).toFloat()
            }
        }
        Model(vocab, alpha, labels, logPrior, logProb)
    }.onFailure { e -> Log.e(TAG, "Load NB model failed: ${e.message}", e) }.getOrNull()

    /**
     * 端侧训练并导出（返回准确率与模型路径）。
     */
    fun trainAndExport(
        context: Context,
        trainCsv: File,
        testCsv: File,
        exportDir: File,
        vocabSize: Int = 32768,
        alpha: Float = 1.0f
    ): Pair<Double, File>? {
        if (!trainCsv.exists() || trainCsv.length() <= 0) return null
        val model = train(trainCsv, vocabSize, alpha)
        val acc = if (testCsv.exists()) evaluate(model, testCsv) else 0.0
        export(model, exportDir, acc)
        return acc to File(exportDir, "model_nb.json")
    }
}