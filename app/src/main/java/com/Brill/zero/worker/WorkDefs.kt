package com.Brill.zero.worker

object WorkDefs {
    // 入参键名（Worker InputData）
    const val KEY_NOTIFICATION_ID = "NOTIFICATION_ID"

    // 唯一任务名（基于数据库自增ID，保证同一通知只跑一次）
    fun nameHigh(id: Long) = "l2l3_high_$id"
    fun nameMedium(id: Long) = "l2l3_medium_$id"

    // 可选：统一打标签便于调试/统计
    const val TAG_L2L3_HIGH = "tag_l2l3_high"
    const val TAG_L2L3_MEDIUM = "tag_l2l3_medium"
}
