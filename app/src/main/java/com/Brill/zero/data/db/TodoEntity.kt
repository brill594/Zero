package com.Brill.zero.data.db


import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueAt: Long?,
    val createdAt: Long,
    val status: String, // OPEN, DONE
    val sourceNotificationKey: String?
)