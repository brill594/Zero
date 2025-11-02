package com.Brill.zero.nls
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.Brill.zero.data.db.NotificationEntity
import com.Brill.zero.data.repo.ZeroRepository
import com.Brill.zero.domain.model.Priority
import com.Brill.zero.ml.PriorityClassifier
import com.Brill.zero.worker.L2L3ProcessWorker // [!!!] V26 修复: 引入新 Worker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.util.Log
/**
 * [!] V26 修复:
 * NLS (服务) 的职责被大大减轻。
 * 它现在只运行超轻量级的 L1 (Gatekeeper) 模型。
 * 它不再运行 L2 或 L3。
 * 它将所有 HIGH 和 MEDIUM 任务抛给 WorkManager 在后台处理。
 */
class ZeroNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var repo: ZeroRepository
    private lateinit var stage1: PriorityClassifier
    private lateinit var workManager: WorkManager

    override fun onCreate() {
        super.onCreate()
        repo = ZeroRepository.get(applicationContext)
        stage1 = PriorityClassifier(applicationContext)
        workManager = WorkManager.getInstance(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val full = listOfNotNull(title, text).joinToString(" · ")

        // 1. 运行 L1 (守门员)
        val priority = stage1.predictPriority(full)

        val entity = NotificationEntity(
            key = sbn.key,
            pkg = sbn.packageName,
            title = title,
            text = text,
            postedAt = System.currentTimeMillis(),
            priority = priority.name
        )

        scope.launch {
            // 2. 保存通知到数据库
            val id = repo.saveNotification(entity)
            val input = workDataOf(com.Brill.zero.worker.WorkDefs.KEY_NOTIFICATION_ID to id)

            when (priority) {
                Priority.HIGH -> {
                    // 高优先级：尽快执行。超出加速配额则自动降级为普通一次性任务
                    val req = OneTimeWorkRequestBuilder<com.Brill.zero.worker.L2L3ProcessWorker>()
                        .setInputData(input)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag(com.Brill.zero.worker.WorkDefs.TAG_L2L3_HIGH)
                        .build()

                    // 去重：同一通知ID只保留一个任务（REPLACE 以最新为准）
                    workManager.enqueueUniqueWork(
                        com.Brill.zero.worker.WorkDefs.nameHigh(id),
                        ExistingWorkPolicy.REPLACE,
                        req
                    )
                }

                Priority.MEDIUM -> {
                    // 中优先级：等设备“空闲+充电”再跑，省电
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true) // API 23+ 有效
                        .build()

                    val req = OneTimeWorkRequestBuilder<com.Brill.zero.worker.L2L3ProcessWorker>()
                        .setInputData(input)
                        .setConstraints(constraints)
                        .addTag(com.Brill.zero.worker.WorkDefs.TAG_L2L3_MEDIUM)
                        .build()

                    // 去重：第一次排队成功后，后续相同通知忽略（KEEP）
                    workManager.enqueueUniqueWork(
                        com.Brill.zero.worker.WorkDefs.nameMedium(id),
                        ExistingWorkPolicy.KEEP,
                        req
                    )
                }

                Priority.LOW -> {
                    // 低优先级：仅存档，不入队
                }
            }

            // 4. 更新小组件 (不变)
            com.Brill.zero.widget.ZeroWidget.updateAll(applicationContext)
        }
    }

    // [!] V26 修复: 移除了 processAndPush, 因为 NLS 不再处理 L2/L3
}