package com.attendancefr.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.attendancefr.data.local.entity.FaceEmbeddingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceEmbeddingDao {
    @Query("SELECT * FROM face_embeddings")
    suspend fun getAll(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE studentId = :studentId")
    suspend fun getForStudent(studentId: Long): List<FaceEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM face_embeddings WHERE studentId = :studentId")
    suspend fun countForStudent(studentId: Long): Int

    @Query("SELECT studentId, COUNT(*) AS cnt FROM face_embeddings GROUP BY studentId")
    fun observeCounts(): Flow<List<EmbeddingCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FaceEmbeddingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FaceEmbeddingEntity>)

    @Query("DELETE FROM face_embeddings WHERE studentId = :studentId")
    suspend fun deleteForStudent(studentId: Long)
}

data class EmbeddingCount(
    val studentId: Long,
    val cnt: Int,
)
