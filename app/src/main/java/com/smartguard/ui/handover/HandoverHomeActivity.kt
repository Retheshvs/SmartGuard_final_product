package com.smartguard.ui.handover

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityHandoverBinding
import com.smartguard.deviceowner.SmartGuardDeviceAdminReceiver
import com.smartguard.handover.HandoverSession
import com.smartguard.policy.SessionController
import com.smartguard.ui.MainActivity
import com.smartguard.ui.ParentAuthGate
import com.smartguard.ui.padForSystemBars
import com.smartguard.util.SgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lent phone's home screen: only the apps the parent picked, pinned in kiosk mode (Device Owner),
 * so Home, Recents, notifications and every other app are out of reach. The lock screen and power
 * menu still work (emergency calls). "End hand-over" needs a parent's face.
 */
class HandoverHomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SG-Handover"

        fun intent(context: Context) = Intent(context, HandoverHomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private lateinit var binding: ActivityHandoverBinding
    private val adapter = HomeAppsAdapter(selectable = false)
    private val parentAuth = ParentAuthGate(this)
    private var apps: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HandoverSession.isActive(this)) {
            finish()
            return
        }
        binding = ActivityHandoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        // Nowhere to go back to while the phone is lent out.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        apps = HandoverSession.packages(this)
        binding.tvTitle.text = "Apps you can use"
        binding.tvSubtitle.text = HandoverSession.parentName(this)?.let { "Lent to you by $it" } ?: "Lent by a parent"
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = adapter
        binding.btnSecondary.visibility = View.GONE
        binding.btnPrimary.text = "End hand-over"
        binding.btnPrimary.setIconResource(R.drawable.ic_lock)
        binding.btnPrimary.setOnClickListener { endHandover() }

        lifecycleScope.launch {
            adapter.submit(withContext(Dispatchers.IO) { loadHomeApps(applicationContext, packages = apps) })
        }
    }

    override fun onResume() {
        super.onResume()
        if (HandoverSession.isActive(this)) enterKiosk() else finish()
    }

    private fun isDeviceOwner() =
        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(packageName)

    private fun inLockTask() =
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun enterKiosk() {
        try {
            if (isDeviceOwner()) {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, SmartGuardDeviceAdminReceiver::class.java)
                dpm.setLockTaskPackages(admin, (apps + packageName).toTypedArray())
                // Keep the lock screen and the power menu (emergency calls); everything else is off.
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            }
            if (!inLockTask()) {
                startLockTask() // without Device Owner Android asks to confirm "pin this app"
                SgLog.i(TAG, "Kiosk on with ${apps.size} apps")
            }
        } catch (e: Exception) {
            SgLog.w(TAG, "Kiosk failed: ${e.message}")
        }
    }

    /** A parent's face ends the hand-over and brings back parent mode. */
    private fun endHandover() {
        parentAuth.requireWithParent("End the hand-over") { parentId ->
            lifecycleScope.launch {
                runCatching { if (inLockTask()) stopLockTask() }
                HandoverSession.end(applicationContext)
                val parent = withContext(Dispatchers.IO) { SmartGuardApp.instance.profileRepository.getProfileById(parentId) }
                if (parent != null) SessionController.applyConfirmedMatch(parent)
                SgLog.i(TAG, "Hand-over ended by ${parent?.name ?: "parent"}")
                startActivity(Intent(this@HandoverHomeActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }
}
