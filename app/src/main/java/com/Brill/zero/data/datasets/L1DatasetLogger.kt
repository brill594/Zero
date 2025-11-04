package com.brill.zero.data.datasets

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 追加 L1 训练样本到 JSONL（每行一个 JSON 对象）。
 * 运行时不可写 assets，改写入 app 私有目录：files/datasets/L1.csv。
 */
object L1DatasetLogger {
    private const val TAG = "L1DatasetLogger"

    /** 返回当前设备上可写入的 L1 文件路径 */
    fun currentFile(context: Context): File {
        val dir = File(context.filesDir, "datasets")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "L1.csv")
    }

    /** 追加一条记录（IO 线程） */
    suspend fun append(context: Context, text: String, labelPriorityZh: String) = withContext(Dispatchers.IO) {
        runCatching {
            val file = currentFile(context)
            val obj = JSONObject().apply {
                put("text", text)
                put("label_priority", labelPriorityZh)
            }
            FileOutputStream(file, true).use { out ->
                out.write(obj.toString().toByteArray())
                out.write('\n'.code)
            }
        }.onFailure { e ->
            Log.e(TAG, "Append failed: ${e.message}", e)
        }.onSuccess {
            Log.d(TAG, "Appended L1 sample to ${currentFile(context).absolutePath}")
        }
    }
}