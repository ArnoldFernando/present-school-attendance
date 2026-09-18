package com.attendancefr.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "face_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["studentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["studentId"])],
)
data class FaceEmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: Long,
    /** Little-endian IEEE-754 float bytes of a 128-d (or 512-d) embedding. */
    val embeddingVector: ByteArray,
    val dateAdded: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbeddingEntity) return false
        return id == other.id &&
            studentId == other.studentId &&
            dateAdded == other.dateAdded &&
            embeddingVector.contentEquals(other.embeddingVector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + studentId.hashCode()
        result = 31 * result + embeddingVector.contentHashCode()
        result = 31 * result + dateAdded.hashCode()
        return result
    }
}
