package com.brill.zero.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE priority = :p ORDER BY postedAt DESC LIMIT :limit")
    fun streamByPriority(p: String, limit: Int = 50): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE processed = 0 AND priority = 'MEDIUM' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun nextUnprocessedMedium(limit: Int = 25): List<NotificationEntity>

    @Query("UPDATE notifications SET processed = 1 WHERE id IN (:ids)")
    suspend fun markProcessed(ids: List<Long>)

    @Query("SELECT * FROM notifications WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotificationEntity?

    @Query("DELETE FROM notifications")
    suspend fun clearAll()
}
