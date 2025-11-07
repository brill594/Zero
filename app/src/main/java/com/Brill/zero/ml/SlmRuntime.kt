package com.brill.zero.ml

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全局 SLM 运行时管理：
 * - App 打开时预加载（冷启动加速）
 * - 进入后台后，空闲 2 分钟自动释放（降内存）
 * - Worker 使用前后打点，防止空闲释放期间被误释放
 */
object SlmRuntime : DefaultLifecycleObserver {
    private lateinit var appCtx: Context
    @Volatile private var processor: L3_SLM_Processor? = null
    @Volatile private var inUseCount: Int = 0
    @Volatile private var lastUseAt: Long = 0L
    @Volatile private var inBackground: Boolean = false
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    @Volatile private var releaseJob: Job? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        // 监听进程级生命周期：前后台切换
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    /** 预加载（冷启动提前加载模型） */
    fun warmStart() {
        val p = processor ?: L3_SLM_Processor(appCtx).also { processor = it }
        runCatching {
            val ok = p.preload()
            Log.i("ZeroSLM", "warmStart preload=${ok}")
        }.onFailure { t -> Log.w("ZeroSLM", "warmStart failed: ${t.message}") }
    }

    /** 获取处理器实例（如未初始化会自动预热） */
    fun obtain(context: Context): L3_SLM_Processor {
        if (!::appCtx.isInitialized) init(context)
        if (processor == null) warmStart()
        return processor!!
    }

    /** 使用打点：开始 */
    fun markUseStart() {
        inUseCount += 1
        lastUseAt = System.currentTimeMillis()
        releaseJob?.cancel()
    }

    /** 使用打点：结束 */
    fun markUseEnd() {
        inUseCount = (inUseCount - 1).coerceAtLeast(0)
        lastUseAt = System.currentTimeMillis()
        // 若已在后台且当前不在使用，重新安排空闲释放
        if (inUseCount == 0 && inBackground) scheduleReleaseAfterIdle()
    }

    /** 后台空闲释放调度（默认 2 分钟） */
    fun scheduleReleaseAfterIdle(delayMs: Long = 120_000L) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(delayMs)
            val idleFor = System.currentTimeMillis() - lastUseAt
            if (inUseCount == 0 && idleFor >= delayMs) {
                runCatching {
                    processor?.release()
                    processor = null
                    Log.i("ZeroSLM", "released after idle=${idleFor}ms")
                }.onFailure { t -> Log.w("ZeroSLM", "release failed: ${t.message}") }
            } else {
                Log.i("ZeroSLM", "skip release: inUse=$inUseCount idleFor=${idleFor}ms")
            }
        }
    }

    // LifecycleObserver 回调：前台 → 预热；后台 → 2 分钟后释放
    override fun onStart(owner: LifecycleOwner) {
        inBackground = false
        warmStart()
    }
    override fun onStop(owner: LifecycleOwner) {
        inBackground = true
        scheduleReleaseAfterIdle()
    }
}