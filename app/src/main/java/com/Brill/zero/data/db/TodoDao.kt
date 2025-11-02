package com.Brill.zero.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TodoEntity): Long

    @Query("SELECT * FROM todos WHERE status = 'OPEN' ORDER BY dueAt IS NULL, dueAt ASC")
    fun streamOpen(): Flow<List<TodoEntity>>

    @Query("UPDATE todos SET status = 'DONE' WHERE id = :id")
    suspend fun markDone(id: Long)

    @Query("DELETE FROM todos")
    suspend fun clearAll()
}
