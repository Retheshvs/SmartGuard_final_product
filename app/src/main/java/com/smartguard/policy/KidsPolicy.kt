package com.smartguard.policy

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Rules shared by every CHILD / TEEN session (enrolled or age-estimated guest).
 */
object KidsPolicy {

    /**
     * Always blocked for children and teens: these are the ways to switch protection off
     * (disable accessibility, force-stop, uninstall) or install new apps. Parents are unaffected.
     */
    val ALWAYS_BLOCKED: Set<String> = setOf(
        "com.android.settings",
        "com.android.vending",                   // Google Play Store
        "com.bbk.appstore",                      // vivo App Store
        "com.iqoo.secure",                       // vivo/iQOO i Manager (can kill/disable apps)
        "com.vivo.safecenter",
        "com.vivo.permissionmanager",
        "com.android.packageinstaller",          // uninstall / install dialogs
        "com.google.android.packageinstaller"
    )

    /** Categories restricted by default when a new CHILD profile is enrolled (parent can change). */
    val DEFAULT_CHILD_BLOCKED_CATEGORIES = listOf("social", "browser")

    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type

    fun parse(json: String?): Set<String> = try {
        if (json.isNullOrBlank()) emptySet() else (gson.fromJson<List<String>>(json, listType) ?: emptyList()).toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun toJson(packages: Collection<String>): String = gson.toJson(packages.sorted())

    fun defaultBlockedFor(role: UserRole): Set<String> = when (role) {
        UserRole.CHILD -> DEFAULT_CHILD_BLOCKED_CATEGORIES.flatMap { AppCategories.packagesFor(it) }.toSet()
        else -> emptySet()
    }
}
