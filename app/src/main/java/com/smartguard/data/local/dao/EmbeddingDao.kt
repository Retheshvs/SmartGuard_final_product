package com.smartguard.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.smartguard.data.local.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: EmbeddingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>)

    @Query("SELECT * FROM embeddings WHERE profileId = :profileId")
    suspend fun getEmbeddingsForProfile(profileId: Long): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE profileId = :profileId")
    suspend fun deleteEmbeddingsForProfile(profileId: Long)

    @Query("SELECT COUNT(*) FROM embeddings WHERE profileId = :profileId AND source = :source")
    suspend fun countBySource(profileId: Long, source: String): Int

    @Query("SELECT MAX(createdAt) FROM embeddings WHERE profileId = :profileId AND source = 'LEARNED'")
    suspend fun lastLearnedAt(profileId: Long): Long?

    @Query(
        "DELETE FROM embeddings WHERE id IN (SELECT id FROM embeddings WHERE profileId = :profileId " +
            "AND source = 'LEARNED' ORDER BY createdAt ASC LIMIT :count)"
    )
    suspend fun deleteOldestLearned(profileId: Long, count: Int)

    @Query("DELETE FROM embeddings WHERE profileId = :profileId AND source = 'LEARNED'")
    suspend fun deleteLearned(profileId: Long)
}
