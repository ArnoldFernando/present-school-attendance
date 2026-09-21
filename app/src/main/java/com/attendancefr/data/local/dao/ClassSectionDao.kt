package com.attendancefr.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.attendancefr.data.local.entity.ClassSectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClassSectionDao {
    @Query("SELECT * FROM class_sections ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ClassSectionEntity>>

    @Query("SELECT * FROM class_sections ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<ClassSectionEntity>

    @Query("SELECT * FROM class_sections WHERE id = :id")
    suspend fun getById(id: Long): ClassSectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ClassSectionEntity): Long

    @Update
    suspend fun update(entity: ClassSectionEntity)

    @Delete
    suspend fun delete(entity: ClassSectionEntity)

    @Query("DELETE FROM class_sections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM class_sections WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ClassSectionEntity?

    @Query("DELETE FROM class_sections WHERE name = :name")
    suspend fun deleteByName(name: String)
}

