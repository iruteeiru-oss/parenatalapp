package com.device.guardian.service.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(): List<MessageEntity>

    @Query("UPDATE messages SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE content = :content 
        AND chatName = :chatName 
        AND timestamp > :since
    """)
    suspend fun countDuplicates(content: String, chatName: String, since: Long): Int

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 200")
    fun observeRecent(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
