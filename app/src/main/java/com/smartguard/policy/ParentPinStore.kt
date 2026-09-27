package com.smartguard.policy

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Parent override PIN, stored only as a salted SHA-256 hash on-device.
 * Defaults to 1234 until the parent changes it in Manage Profiles (the UI nags while it's default).
 */
class ParentPinStore(context: Context) {

    private val prefs = context.getSharedPreferences("smartguard_pin", Context.MODE_PRIVATE)

    val isDefault: Boolean get() = !prefs.contains(KEY_HASH)

    fun verify(pin: String): Boolean {
        val hash = prefs.getString(KEY_HASH, null) ?: return pin == DEFAULT_PIN
        val salt = prefs.getString(KEY_SALT, "") ?: ""
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), hash.toByteArray())
    }

    /** Returns false if the new PIN is not 4-8 digits. */
    fun setPin(newPin: String): Boolean {
        if (!newPin.matches(Regex("""\d{4,8}"""))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(salt, newPin)).apply()
        return true
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val DEFAULT_PIN = "1234"
    }
}
