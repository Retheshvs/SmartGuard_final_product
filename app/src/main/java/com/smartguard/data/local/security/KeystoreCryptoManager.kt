package com.smartguard.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Handles hardware-backed Android Keystore encryption/decryption for face vector embeddings.
 * Hard Constraint: No raw face vectors are ever persisted to disk unencrypted.
 */
class KeystoreCryptoManager {

    companion object {
        private const val KEY_ALIAS = "SmartGuardBiometricKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        createKeyIfNeeded()
    }

    private fun createKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Offline background verification support
                .build()

            keyGenerator.init(parameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts a float array (face embedding) into encrypted bytes and an IV.
     */
    fun encryptVector(vector: FloatArray): EncryptedResult {
        val byteBuffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in vector) {
            byteBuffer.putFloat(f)
        }
        val rawBytes = byteBuffer.array()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(rawBytes)

        return EncryptedResult(encryptedBytes = encryptedBytes, iv = iv)
    }

    /**
     * Decrypts encrypted bytes back into a FloatArray face embedding.
     */
    fun decryptVector(encryptedBytes: ByteArray, iv: ByteArray, dimension: Int = 128): FloatArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedRawBytes = cipher.doFinal(encryptedBytes)

        val byteBuffer = ByteBuffer.wrap(decryptedRawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(dimension)
        for (i in 0 until dimension) {
            if (byteBuffer.hasRemaining()) {
                floats[i] = byteBuffer.getFloat()
            }
        }
        return floats
    }

    data class EncryptedResult(
        val encryptedBytes: ByteArray,
        val iv: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedResult

            if (!encryptedBytes.contentEquals(other.encryptedBytes)) return false
            if (!iv.contentEquals(other.iv)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = encryptedBytes.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }
}
