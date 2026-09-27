package com.smartguard.policy

import android.content.Context

/** Per-child "Kiosk mode" switch (young children): pinned to SmartGuard's kid home. */
class KioskStore(context: Context) {

    private val prefs = context.getSharedPreferences("smartguard_kiosk", Context.MODE_PRIVATE)

    fun isOn(profileId: Long?): Boolean = profileId != null && profileId > 0 && prefs.getBoolean(key(profileId), false)

    fun set(profileId: Long, on: Boolean) {
        prefs.edit().putBoolean(key(profileId), on).apply()
    }

    private fun key(id: Long) = "kiosk_$id"
}
