package com.smartguard.deviceowner

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.smartguard.util.SgLog

/**
 * Device Owner (anti-bypass) features. Everything here is a no-op unless SmartGuard has been
 * provisioned with:
 *   adb shell dpm set-device-owner com.smartguard/.deviceowner.SmartGuardDeviceAdminReceiver
 *
 * Lockdown:
 *  - SmartGuard can't be uninstalled
 *  - factory reset and safe-mode boot are disabled (safe mode would start the phone without SmartGuard)
 *  - camera + notification permissions are locked "granted" so a child can't revoke them
 *  - SmartGuard may use lock-task (kiosk) mode
 *
 * [releaseDeviceOwner] undoes all of it and gives up Device Owner, so the phone can be handed
 * back (e.g. the event's loaner phone) without a factory reset.
 */
class DeviceOwnerManager(private val context: Context) {

    companion object {
        private const val TAG = "SG-DeviceOwner"
    }

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, SmartGuardDeviceAdminReceiver::class.java)

    private val lockedPermissions: List<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun isDeviceAdmin(): Boolean = dpm.isAdminActive(adminComponent)

    /** True if the lockdown is currently applied (uninstall blocked). */
    fun isLockdownActive(): Boolean = try {
        isDeviceOwner() && dpm.isUninstallBlocked(adminComponent, context.packageName)
    } catch (e: Exception) {
        false
    }

    fun applyAntiBypassLockdown(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            lockedPermissions.forEach {
                dpm.setPermissionGrantState(
                    adminComponent, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
            SgLog.i(TAG, "Anti-bypass lockdown applied")
            true
        } catch (e: Exception) {
            SgLog.w(TAG, "Lockdown failed: ${e.message}")
            false
        }
    }

    fun removeAntiBypassLockdown(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, false)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            dpm.setLockTaskPackages(adminComponent, arrayOf())
            lockedPermissions.forEach {
                dpm.setPermissionGrantState(
                    adminComponent, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                )
            }
            SgLog.i(TAG, "Anti-bypass lockdown removed")
            true
        } catch (e: Exception) {
            SgLog.w(TAG, "Removing lockdown failed: ${e.message}")
            false
        }
    }

    /**
     * Removes every restriction and gives up Device Owner. After this SmartGuard is an ordinary app
     * again and can be uninstalled normally — no factory reset needed.
     */
    @Suppress("DEPRECATION")
    fun releaseDeviceOwner(): Boolean {
        if (!isDeviceOwner()) return false
        removeAntiBypassLockdown()
        DeviceRules.releaseAll(context) // un-pause every app and drop child-session rules
        return try {
            dpm.clearDeviceOwnerApp(context.packageName)
            SgLog.i(TAG, "Device Owner released")
            true
        } catch (e: Exception) {
            SgLog.w(TAG, "Release failed: ${e.message}")
            false
        }
    }
}
