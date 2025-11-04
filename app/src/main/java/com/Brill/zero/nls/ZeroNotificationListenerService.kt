package com.brill.zero.nls
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.workDataOf

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.brill.zero.data.db.NotificationEntity
import com.brill.zero.data.repo.ZeroRepository
import com.brill.zero.domain.model.Priority
import com.brill.zero.ml.PriorityClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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
        val text  = extras.getCharSequence("android.text")?.toString()
        val full  = listOfNotNull(title, text).joinToString(" · ")

        // 统一放到后台协程里：先做 L1 推理，再入库和入队
        scope.launch(Dispatchers.IO) {
            // 1) L1（守门员）分类 —— 现在是挂起函数，安全地在后台线程跑
            val priority = stage1.predictPriority(full)

            // 2) 保存通知到数据库
            val entity = NotificationEntity(
                key = sbn.key,
                pkg = sbn.packageName,
                title = title,
                text  = text,
                postedAt = System.currentTimeMillis(),
                priority = priority.name
            )
            val id = repo.saveNotification(entity)

            // 3) 依据优先级入队后台任务
            val input = workDataOf(
                com.brill.zero.worker.WorkDefs.KEY_NOTIFICATION_ID to id,
                com.brill.zero.worker.WorkDefs.KEY_PRIORITY to priority.name
            )
            when (priority) {
                Priority.HIGH -> {
                    val req = OneTimeWorkRequestBuilder<com.brill.zero.worker.L2L3ProcessWorker>()
                        .setInputData(input)
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .addTag(com.brill.zero.worker.WorkDefs.TAG_L2L3_HIGH)
                        .build()
                    workManager.enqueueUniqueWork(
                        com.brill.zero.worker.WorkDefs.nameHigh(id),
                        ExistingWorkPolicy.REPLACE,
                        req
                    )
                }
                Priority.MEDIUM -> {
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                    val req = OneTimeWorkRequestBuilder<com.brill.zero.worker.L2L3ProcessWorker>()
                        .setInputData(input)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                        .addTag(com.brill.zero.worker.WorkDefs.TAG_L2L3_MEDIUM)
                        .build()
                    workManager.enqueueUniqueWork(
                        com.brill.zero.worker.WorkDefs.nameMedium(id),
                        ExistingWorkPolicy.KEEP,
                        req
                    )
                }
                Priority.LOW -> {
                    // 低优先级仅存档
                }
            }

            // 4) 刷新小组件（这里在协程里调用也没问题）
            com.brill.zero.widget.ZeroWidget.updateAll(applicationContext)
        }
    }


    // [!] V26 修复: 移除了 processAndPush, 因为 NLS 不再处理 L2/L3
}