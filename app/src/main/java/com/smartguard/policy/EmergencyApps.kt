package com.smartguard.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Emergency apps: calls, messages, camera, WhatsApp and installed safety/SOS apps.
 *
 * They are ALWAYS usable for children and teens — when screen time is over, before anyone is
 * verified, during curfews, and regardless of restricted-app lists. Detected per device (vivo, Pixel,
 * Samsung… all use different package names), so the default dialer/SMS/camera of THIS phone is used.
 */
object EmergencyApps {

    /** Well-known package names (used even if the system query misses them). */
    private val KNOWN = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",                       // WhatsApp Business
        "com.android.emergency",
        "com.google.android.apps.safetyhub",      // Google Personal Safety
        "com.vivo.sos",
        "com.android.mms",                        // AOSP / vivo messages
        "com.google.android.apps.messaging",      // Google Messages
        "com.android.camera",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.contacts"
    )

    /** Installed apps whose package or name contains one of these are treated as safety apps. */
    private val SAFETY_KEYWORDS = listOf("sos", "safety", "emergenc", "rescue", "life360", "raksha", "112")

    fun isKnown(pkg: String) = pkg in KNOWN

    /** Resolves this device's emergency apps. Cheap enough to call on service connect / screen open. */
    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val result = KNOWN.filterTo(mutableSetOf()) { isInstalled(pm, it) }

        try {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { result += it }
        } catch (e: Exception) { /* ignore */ }
        // Messages = the phone's default SMS app (Android's SMS role holder).
        val defaultSms = try {
            Telephony.Sms.getDefaultSmsPackage(context)
        } catch (e: Exception) {
            null
        }
        defaultSms?.let { result += it }

        // Built-in dialer and camera apps. Only SYSTEM apps count: any app can register for these
        // intents, and a third-party one must not become "always allowed" that way.
        listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        ).forEach { intent ->
            try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .filter { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                    .forEach { result += it.activityInfo.packageName }
            } catch (e: Exception) { /* ignore */ }
        }

        // Only if no default SMS app is set: fall back to apps that can receive SMS. (Not always,
        // because backup tools like vivo EasyShare also register for SMS_DELIVER to restore messages.)
        if (defaultSms == null) {
            try {
                pm.queryBroadcastReceivers(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION), 0)
                    .filterNot { it.activityInfo.packageName.contains("easyshare", ignoreCase = true) }
                    .forEach { result += it.activityInfo.packageName }
            } catch (e: Exception) { /* ignore */ }
        }

        // Installed safety / SOS apps (by package name or visible label).
        try {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                val label = ri.loadLabel(pm).toString().lowercase()
                if (SAFETY_KEYWORDS.any { pkg.lowercase().contains(it) || label.contains(it) }) result += pkg
            }
        } catch (e: Exception) { /* ignore */ }

        return result - context.packageName
    }

    private fun isInstalled(pm: PackageManager, pkg: String) = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }
}
