package com.smartguard.data.repository

import com.smartguard.data.local.dao.EmbeddingDao
import com.smartguard.data.local.dao.ProfileDao
import com.smartguard.data.local.entity.EmbeddingEntity
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.data.local.security.KeystoreCryptoManager
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing profiles and encrypted facial embeddings.
 * Interacts with KeystoreCryptoManager to encrypt vectors before saving to database
 * and decrypt them upon retrieval.
 */
class ProfileRepository(
    private val profileDao: ProfileDao,
    private val embeddingDao: EmbeddingDao,
    private val cryptoManager: KeystoreCryptoManager
) {

    data class DecryptedEmbedding(
        val embeddingId: Long,
        val profileId: Long,
        val vector: FloatArray
    )

    fun getAllProfilesFlow(): Flow<List<ProfileEntity>> = profileDao.getAllProfilesFlow()

    suspend fun getAllProfiles(): List<ProfileEntity> = profileDao.getAllProfiles()

    suspend fun getProfileById(id: Long): ProfileEntity? = profileDao.getProfileById(id)

    suspend fun getOwnerProfile(): ProfileEntity? = profileDao.getOwnerProfile()

    suspend fun insertProfile(profile: ProfileEntity): Long = profileDao.insertProfile(profile)

    suspend fun updateProfile(profile: ProfileEntity) = profileDao.updateProfile(profile)

    suspend fun deleteProfile(profile: ProfileEntity) {
        embeddingDao.deleteEmbeddingsForProfile(profile.id)
        profileDao.deleteProfile(profile)
        invalidateGallery()
    }

    /**
     * Decrypted face samples, kept in this process's memory between checks. Decrypting means one
     * hardware-keystore operation per sample, which would otherwise run at the start of every check
     * (and grows as profiles learn new looks). Cleared on every change to face data.
     */
    @Volatile
    private var galleryCache: List<DecryptedEmbedding>? = null

    private fun invalidateGallery() {
        galleryCache = null
    }

    /**
     * Encrypts and saves a list of 5-10 raw face embedding vectors for a profile.
     */
    suspend fun saveProfileEmbeddings(profileId: Long, rawVectors: List<FloatArray>) {
        val entities = rawVectors.map { vector ->
            val encrypted = cryptoManager.encryptVector(vector)
            EmbeddingEntity(
                profileId = profileId,
                encryptedVector = encrypted.encryptedBytes,
                iv = encrypted.iv,
                dimension = vector.size
            )
        }
        embeddingDao.insertEmbeddings(entities)
        invalidateGallery()
    }

    /** Replaces a profile's face samples (re-enrollment), keeping all of its settings. */
    suspend fun replaceProfileEmbeddings(profileId: Long, rawVectors: List<FloatArray>) {
        embeddingDao.deleteEmbeddingsForProfile(profileId)
        invalidateGallery()
        saveProfileEmbeddings(profileId, rawVectors)
    }

    suspend fun getEmbeddingCount(profileId: Long): Int = embeddingDao.getEmbeddingsForProfile(profileId).size

    // ---- face profiles that grow with the person (see FaceProfileLearner) ----

    data class FaceDataSummary(val enrolled: Int, val learned: Int, val lastLearnedAt: Long?)

    suspend fun faceDataSummary(profileId: Long) = FaceDataSummary(
        enrolled = embeddingDao.countBySource(profileId, EmbeddingEntity.SOURCE_ENROLLED),
        learned = embeddingDao.countBySource(profileId, EmbeddingEntity.SOURCE_LEARNED),
        lastLearnedAt = embeddingDao.lastLearnedAt(profileId)
    )

    suspend fun lastLearnedAt(profileId: Long): Long? = embeddingDao.lastLearnedAt(profileId)

    /** Stores a learned look (encrypted like every sample), keeping at most [maxLearned]: oldest go first. */
    suspend fun addLearnedEmbedding(profileId: Long, vector: FloatArray, maxLearned: Int) {
        val encrypted = cryptoManager.encryptVector(vector)
        embeddingDao.insertEmbedding(
            EmbeddingEntity(
                profileId = profileId,
                encryptedVector = encrypted.encryptedBytes,
                iv = encrypted.iv,
                dimension = vector.size,
                source = EmbeddingEntity.SOURCE_LEARNED
            )
        )
        val extra = embeddingDao.countBySource(profileId, EmbeddingEntity.SOURCE_LEARNED) - maxLearned
        if (extra > 0) embeddingDao.deleteOldestLearned(profileId, extra)
        invalidateGallery()
    }

    /** Forgets everything learned for a profile; the enrolled face stays. */
    suspend fun clearLearnedEmbeddings(profileId: Long) {
        embeddingDao.deleteLearned(profileId)
        invalidateGallery()
    }

    /**
     * Retrieves and decrypts all face embeddings stored across all enrolled profiles.
     */
    suspend fun getAllDecryptedEmbeddings(): List<DecryptedEmbedding> =
        galleryCache ?: decryptAllEmbeddings().also { galleryCache = it }

    /** Always decrypts from storage (bypasses the cache). */
    suspend fun decryptAllEmbeddings(): List<DecryptedEmbedding> {
        val entities = embeddingDao.getAllEmbeddings()
        return entities.mapNotNull { entity ->
            try {
                val decrypted = cryptoManager.decryptVector(
                    encryptedBytes = entity.encryptedVector,
                    iv = entity.iv,
                    dimension = entity.dimension
                )
                DecryptedEmbedding(
                    embeddingId = entity.id,
                    profileId = entity.profileId,
                    vector = decrypted
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
