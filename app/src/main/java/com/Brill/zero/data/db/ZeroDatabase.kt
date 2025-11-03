package com.brill.zero.data.db


import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


@Database(
    entities = [NotificationEntity::class, TodoEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ZeroDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun todoDao(): TodoDao


    companion object {
        fun build(context: Context) = Room.databaseBuilder(
            context, ZeroDatabase::class.java, "zero.db"
        ).build()
    }
}