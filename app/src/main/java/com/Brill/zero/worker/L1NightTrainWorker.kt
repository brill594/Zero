package com.brill.zero.worker

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.work.*
import com.brill.zero.data.datasets.L1DatasetLogger
import com.brill.zero.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * L1NightTrainWorker：夜间训练（或手动触发）
 * - 合并 L1.csv 到 assets/train_L1.csv 与 assets/test_L1.csv
 * - 训练进度通过 AppSettings(KEY_L1_TRAIN_PROGRESS_EPOCH) 暴露给 UI（0..80）
 * - 自动触发条件：充电 + 夜间(0..6) + 设备空闲；且 L1.csv 相对上次有变化
 * - 训练结果采用：accuracy >= 0.9 时，将导出模型复制为 noBackupDir/models/L1_learned.tflite 并置 useLearned=true
 */
class L1NightTrainWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val force = inputData.getBoolean(KEY_FORCE, false)

        // 条件 gating（仅自动触发时生效）
        if (!force) {
            if (!isCharging(ctx) || !isNightTime() || !isIdle(ctx)) {
                Log.i(TAG, "Gating not satisfied: charging/night/idle required")
                return@withContext Result.retry()
            }
        }

        val l1File = L1DatasetLogger.currentFile(ctx)
        if (!l1File.exists()) {
            Log.w(TAG, "L1.csv not found; nothing to train")
            return@withContext Result.success()
        }

        // 数据集签名变化检测
        val sig = md5Of(l1File)
        val lastSig = AppSettings.getL1LastDatasetSig(ctx)
        if (lastSig != null && lastSig == sig) {
            Log.i(TAG, "Dataset unchanged; skip training")
            return@withContext Result.success()
        }

        // 合并数据集到工作目录
        val trainDir = File(ctx.noBackupFilesDir, "training").apply { mkdirs() }
        val outTrain = File(trainDir, "train_L1.csv")
        val outTest = File(trainDir, "test_L1.csv")
        mergeDatasets(ctx, l1File, outTrain, outTest)

        // 写训练请求（供外部脚本发现并执行）
        writeTrainingRequest(trainDir)

        // 记录训练开始时间，供 UI 预计剩余时间计算
        AppSettings.setL1TrainStartTs(ctx, System.currentTimeMillis())

        // 模拟训练进度（若外部脚本运行，可由其覆盖该值）
        for (epoch in 1..80) {
            AppSettings.setL1TrainProgressEpoch(ctx, epoch)
            AppSettings.setL1TrainLastUpdateTs(ctx, System.currentTimeMillis())
            delay(100) // 简易进度节奏
        }

        // 读取导出结果并判断是否采用
        val exportDir = File(trainDir, "gatekeeper_export")
        val modelTflite = File(exportDir, "model.tflite")
        val metricsJson = File(exportDir, "metrics.json")
        if (modelTflite.exists() && metricsJson.exists()) {
            runCatching {
                val acc = JSONObject(metricsJson.readText()).optDouble("accuracy", 0.0)
                Log.i(TAG, "Retrain accuracy: %.4f (%.2f%%)".format(acc, acc * 100.0))
                if (acc >= 0.90) {
                    val dstModelDir = File(ctx.noBackupFilesDir, "models").apply { mkdirs() }
                    val dstModel = File(dstModelDir, "L1_learned.tflite")
                    modelTflite.inputStream().use { `in` ->
                        FileOutputStream(dstModel).use { out -> `in`.copyTo(out) }
                    }
                    // 同步保存指标文件，便于在本地模型管理中展示准确率
                    runCatching {
                        val dstMetrics = File(dstModelDir, "L1_learned.metrics.json")
                        metricsJson.inputStream().use { `in` ->
                            FileOutputStream(dstMetrics).use { out -> `in`.copyTo(out) }
                        }
                    }.onFailure { e -> Log.w(TAG, "Copy metrics failed: ${e.message}") }
                    // 采用新模型
                    AppSettings.setUseLearnedL1(ctx, true)
                    AppSettings.setL1SelectedModelPath(ctx, dstModel.absolutePath)
                    AppSettings.setL1LastDatasetSig(ctx, sig)
                    Log.i(TAG, "Learned model adopted (>=90%): ${dstModel.absolutePath}")
                } else {
                    Log.i(TAG, "Accuracy below threshold (<90%); not adopting")
                }
            }.onFailure { e ->
                Log.e(TAG, "Adopt failed: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "Export artifacts not found; model not adopted")
        }

        Result.success()
    }

    private fun isCharging(ctx: Context): Boolean {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return status != 0
    }

    private fun isIdle(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = pm.isInteractive
        val idle = try { pm.isDeviceIdleMode } catch (_: Throwable) { false }
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val locked = km.isKeyguardLocked
        return !interactive && idle && locked
    }

    private fun isNightTime(): Boolean {
        val now = LocalDateTime.now()
        val h = now.hour
        return h in 0..6
    }

    private fun md5Of(file: File): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { `in` ->
            val buf = ByteArray(8192)
            while (true) {
                val n = `in`.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun mergeDatasets(ctx: Context, l1Jsonl: File, outTrain: File, outTest: File) {
        fun writeHeader(f: File) { f.writeText("text,label\n") }
        writeHeader(outTrain)
        writeHeader(outTest)

        // 1) 追加 assets 的 CSV（跳过首行表头），并将文本转换为拼音
        fun parseTwoCols(line: String): Pair<String, String>? {
            // 简易 CSV 解析：处理可能的引号封装，仅两列 text,label
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
                    // 跳过可能的逗号
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

        fun appendCsvAsset(assetName: String, out: File) {
            ctx.assets.open(assetName).bufferedReader().use { br ->
                var first = true
                br.forEachLine { line ->
                    if (first) { first = false; return@forEachLine }
                    val pair = parseTwoCols(line)
                    if (pair != null) {
                        val (origText, label) = pair
                        val pinyin = com.brill.zero.util.PinyinUtil.toPinyin(origText)
                        val outLine = "${escape(pinyin)},${escape(label)}\n"
                        out.appendText(outLine)
                    } else {
                        Log.w(TAG, "Skip malformed CSV asset line")
                    }
                }
            }
        }
        appendCsvAsset("datasets/train_L1.csv", outTrain)
        appendCsvAsset("datasets/test_L1.csv", outTest)

        // 2) 将 L1.jsonl 追加到 train 与 test（字段映射：label_priority -> label），并转换文本为拼音
        l1Jsonl.bufferedReader().use { br ->
            br.forEachLine { raw ->
                runCatching {
                    val obj = JSONObject(raw)
                    val text = obj.optString("text")
                    var label = obj.optString("label_priority")
                    if (label.isBlank()) return@runCatching
                    // 归一化 label 到 CSV 体系（JSONL 可能用 "低优先级/垃圾"）
                    if (label == "低优先级/垃圾") label = "低优先级"
                    val pinyin = com.brill.zero.util.PinyinUtil.toPinyin(text)
                    val line = "${escape(pinyin)},${escape(label)}\n"
                    outTrain.appendText(line)
                    outTest.appendText(line)
                }.onFailure { e -> Log.w(TAG, "Skip bad jsonl: ${e.message}") }
            }
        }
    }

    private fun escape(s: String): String {
        val needsQuote = s.contains(',') || s.contains('\n') || s.contains('"')
        val esc = s.replace("\"", "\"\"")
        return if (needsQuote) "\"$esc\"" else esc
    }

    private fun writeTrainingRequest(trainDir: File) {
        runCatching {
            val req = JSONObject().apply {
                put("created_at", System.currentTimeMillis())
                put("cwd", trainDir.absolutePath)
                put("export_dir", "gatekeeper_export")
                put("params", JSONObject().apply {
                    put("epochs", 80)
                    put("batch_size", 32)
                })
            }
            File(trainDir, "training_request.json").writeText(req.toString())
        }.onFailure { e -> Log.w(TAG, "Write request failed: ${e.message}") }
    }

    companion object {
        private const val TAG = "L1NightTrainWorker"
        private const val KEY_FORCE = "force"

        fun enqueueNow(ctx: Context, force: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<L1NightTrainWorker>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }

        /** 安排下一次夜间训练（约定凌晨 2:00）并设置充电+设备空闲约束 */
        fun scheduleNightly(ctx: Context) {
            if (AppSettings.getL1NightlyScheduled(ctx)) return
            val now = LocalDateTime.now()
            var next = now.withHour(2).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMs = ChronoUnit.MILLIS.between(now, next)

            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val req = OneTimeWorkRequestBuilder<L1NightTrainWorker>()
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
            AppSettings.setL1NightlyScheduled(ctx, true)
            Log.i(TAG, "Nightly training scheduled: $next (${delayMs}ms)")
        }
    }
}