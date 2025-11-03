package com.brill.zero.data.db


import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String, // sbn.key for dedupe
    val pkg: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
    val priority: String, // HIGH, MEDIUM, LOW (model output)
    val userPriority: String? = null, // optional override from user
    val processed: Boolean = false,
    val pushed: Boolean = false
)