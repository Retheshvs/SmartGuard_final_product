package com.smartguard.policy

/**
 * User roles defining privilege levels and restriction tiers.
 */
enum class UserRole {
    CHILD,
    TEEN,
    ADULT,
    UNKNOWN_RESTRICTIVE; // Fail-safe default mode

    companion object {
        fun fromString(roleStr: String): UserRole {
            return try {
                valueOf(roleStr.uppercase())
            } catch (e: Exception) {
                UNKNOWN_RESTRICTIVE
            }
        }
    }
}
