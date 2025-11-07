package com.brill.zero.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TodoEntity): Long

    @Query("SELECT * FROM todos WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt ASC")
    fun streamOpen(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt ASC LIMIT 1")
    suspend fun getNextOpen(): TodoEntity?

    @Query("SELECT * FROM todos WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt ASC LIMIT :limit")
    suspend fun getOpen(limit: Int): List<TodoEntity>

    @Query("UPDATE todos SET status = 'DONE' WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("UPDATE todos SET dueAt = :dueAt WHERE id = :id")
    suspend fun setDueAt(id: Long, dueAt: Long?)

    @Query("UPDATE todos SET title = :title, dueAt = :dueAt WHERE id = :id")
    suspend fun setTitleAndDueAt(id: Long, title: String, dueAt: Long?)

    @Query("DELETE FROM todos")
    suspend fun clearAll()
}
