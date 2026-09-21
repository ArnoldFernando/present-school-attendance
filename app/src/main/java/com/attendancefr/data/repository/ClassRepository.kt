package com.attendancefr.data.repository

import com.attendancefr.data.local.dao.ClassSectionDao
import com.attendancefr.data.local.entity.ClassSectionEntity
import com.attendancefr.domain.model.ClassSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassRepository @Inject constructor(
    private val dao: ClassSectionDao,
) {
    fun observeAll(): Flow<List<ClassSection>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<ClassSection> = dao.getAll().map { it.toDomain() }

    suspend fun add(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Class name cannot be empty." }
        return dao.insert(ClassSectionEntity(name = trimmed))
    }

    suspend fun rename(id: Long, name: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(name = name.trim()))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun removeEmptyClasses(activeNames: List<String>) {
        val allClasses = dao.getAll()
        allClasses.forEach { cls ->
            if (cls.name !in activeNames) {
                dao.deleteByName(cls.name)
            }
        }
    }

    private fun ClassSectionEntity.toDomain() = ClassSection(id = id, name = name)
}

