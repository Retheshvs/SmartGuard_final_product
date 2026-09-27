package com.smartguard.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smartguard.SmartGuardApp
import com.smartguard.policy.UserRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every change to SmartGuard's settings must be authorised by a parent's face, every time.
 *
 * Usage (create as a property so it registers before the activity starts):
 *   private val parentAuth = ParentAuthGate(this)
 *   parentAuth.require("Save Prem's rules") { save() }
 *
 * The action runs only after [ParentConfirmActivity] matches an enrolled PARENT. The one exception is
 * first-time setup: before any parent is enrolled there is no face to confirm with.
 */
class ParentAuthGate(private val activity: AppCompatActivity) {

    private var pending: ((Long) -> Unit)? = null

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action = pending
            pending = null
            if (result.resultCode == Activity.RESULT_OK) {
                action?.invoke(result.data?.getLongExtra(ParentConfirmActivity.RESULT_PROFILE_ID, -1L) ?: -1L)
            } else {
                Toast.makeText(activity, "Not changed: a parent's face is needed", Toast.LENGTH_SHORT).show()
            }
        }

    fun require(reason: String, action: () -> Unit) = requireWithParent(reason) { action() }

    /** Like [require], but also hands over the id of the parent who confirmed (-1 in first-time setup). */
    fun requireWithParent(reason: String, action: (parentProfileId: Long) -> Unit) {
        activity.lifecycleScope.launch {
            val hasParent = withContext(Dispatchers.IO) {
                SmartGuardApp.instance.profileRepository.getAllProfiles()
                    .any { UserRole.fromString(it.role) == UserRole.ADULT }
            }
            if (!hasParent) {
                action(-1L) // first-time setup: no parent enrolled yet
                return@launch
            }
            pending = action
            launcher.launch(ParentConfirmActivity.intent(activity, reason))
        }
    }
}
