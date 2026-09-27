package com.smartguard.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["profileId"])]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val encryptedVector: ByteArray,
    val iv: ByteArray,
    val dimension: Int = 128,
    val createdAt: Long = System.currentTimeMillis(),
    /** [SOURCE_ENROLLED] (from enrollment, never changed) or [SOURCE_LEARNED] (added as the face changes). */
    val source: String = SOURCE_ENROLLED
) {
    companion object {
        const val SOURCE_ENROLLED = "ENROLLED"
        const val SOURCE_LEARNED = "LEARNED"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddingEntity

        if (id != other.id) return false
        if (profileId != other.profileId) return false
        if (!encryptedVector.contentEquals(other.encryptedVector)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (dimension != other.dimension) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + profileId.hashCode()
        result = 31 * result + encryptedVector.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + dimension
        return result
    }
}
