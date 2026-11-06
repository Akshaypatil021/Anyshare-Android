package com.example.anyshare.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for transfer_history table with responsive Flow-based queries.
 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM transfer_history WHERE category = :category ORDER BY timestamp DESC")
    fun getHistoryByCategory(category: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM transfer_history WHERE fileName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchAll(query: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM transfer_history WHERE category = :category AND fileName LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchByCategory(category: String, query: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM transfer_history ORDER BY timestamp DESC")
    suspend fun getAllHistoryList(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<HistoryEntity>): List<Long>

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM transfer_history")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM transfer_history")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfer_history WHERE category = :category")
    fun getCategoryCount(category: String): Flow<Int>

    @Query("SELECT SUM(fileSize) FROM transfer_history")
    fun getTotalBytes(): Flow<Long?>
}
