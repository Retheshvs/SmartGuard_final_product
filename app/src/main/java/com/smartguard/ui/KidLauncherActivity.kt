package com.smartguard.ui

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityKidLauncherBinding
import com.smartguard.databinding.ItemLauncherAppBinding
import com.smartguard.deviceowner.SmartGuardDeviceAdminReceiver
import com.smartguard.handover.VerificationLauncher
import com.smartguard.policy.EmergencyApps
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.KioskStore
import com.smartguard.policy.PolicyStore
import com.smartguard.policy.SessionController
import com.smartguard.policy.SessionState
import com.smartguard.policy.UserRole
import com.smartguard.util.SgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The child's own home screen: a friendly hello, a time bar, and big tiles for exactly the apps they
 * may use right now (emergency apps first). Also where a child lands when a restricted app is closed,
 * with a short notice.
 *
 * KIOSK MODE (per child, needs Device Owner): the phone is pinned here — no status bar, notifications,
 * recent apps or home button; only this screen and the allowed apps. A small "Grown-ups" button leaves
 * kiosk mode after a parent's face confirms. The lock screen and power menu still work, so screen-on
 * face checks and emergency calls keep working.
 */
class KidLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SG-KidHome"
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
        const val EXTRA_REASON = "reason"

        @Volatile
        var isVisible = false
            private set

        fun intent(context: Context, blockedPackage: String?, reason: String?) =
            Intent(context, KidLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_BLOCKED_PACKAGE, blockedPackage)
                .putExtra(EXTRA_REASON, reason)
    }

    private data class AppEntry(val pkg: String, val label: String, val icon: Drawable?, val launch: Intent)

    private lateinit var binding: ActivityKidLauncherBinding
    private val adapter = AppsAdapter()
    private val parentAuth = ParentAuthGate(this)
    private var noticeJob: Job? = null
    private var kiosk = false

    /** In kiosk mode, Back does nothing (there is nowhere else to go). */
    private val stayInKiosk = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKidLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        onBackPressedDispatcher.addCallback(this, stayInKiosk)

        binding.rvApps.layoutManager = GridLayoutManager(this, 3)
        binding.rvApps.adapter = adapter
        binding.btnSwitchUser.setOnClickListener { if (kiosk) leaveKiosk() else VerificationLauncher.launchVisibleCheck(this, force = true) }

        showNotice(intent)
        lifecycleScope.launch {
            SmartGuardApp.instance.sessionState
                .distinctUntilChangedBy { s ->
                    listOf(s.activeProfile?.id, s.activeProfile?.blockedPackagesJson, s.activeRole, s.isSessionLocked,
                        s.remainingScreenTimeSeconds <= 0, s.remainingScreenTimeSeconds / 60)
                }
                .collectLatest { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        super.onPause()
        isVisible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showNotice(intent)
    }

    // ------------------------------------------------------------------ rendering

    private suspend fun render(session: SessionState) {
        if (session.activeRole == UserRole.ADULT) {
            setKiosk(false, emptySet())
            finish() // parents use the normal launcher
            return
        }
        val locked = session.activeRole == UserRole.UNKNOWN_RESTRICTIVE || session.isSessionLocked
        val timeUp = !locked && session.remainingScreenTimeSeconds <= 0
        val profile = session.activeProfile
        val name = profile?.name?.substringBefore(" (")?.takeIf { !locked }

        binding.avatar.text = (name ?: "?").take(1).uppercase()
        binding.tvTitle.text = if (name != null) "Hi, $name!" else "Hi there!"
        binding.tvSubtitle.text = when {
            locked -> "Show your face to start"
            timeUp -> "Play time is over for today"
            else -> "What do you want to do?"
        }
        renderTime(session, locked, timeUp)

        val apps = withContext(Dispatchers.IO) { allowedApps(session, emergencyOnly = locked || timeUp) }
        adapter.submit(apps)

        // Kiosk only for an enrolled child who has it switched on (and only with Device Owner).
        val wantKiosk = !locked && session.activeRole == UserRole.CHILD && isDeviceOwner() &&
            KioskStore(applicationContext).isOn(profile?.id)
        setKiosk(wantKiosk, apps.map { it.pkg }.toSet())
    }

    private fun renderTime(session: SessionState, locked: Boolean, timeUp: Boolean) {
        if (locked) {
            binding.timeCard.visibility = View.GONE
            return
        }
        binding.timeCard.visibility = View.VISIBLE
        val budget = (session.screenTimeBudgetMinutes * 60L).coerceAtLeast(1L)
        val left = session.remainingScreenTimeSeconds.coerceIn(0L, budget)
        val minutes = (left + 59) / 60
        binding.tvTimeLeft.text = when {
            timeUp -> "No more play time today"
            minutes == 1L -> "1 minute left to play"
            else -> "$minutes minutes left to play"
        }
        val color = when {
            timeUp -> R.color.accent_rose
            left <= 600 -> R.color.accent_amber
            else -> R.color.accent_emerald
        }
        binding.timeBar.setIndicatorColor(ContextCompat.getColor(this, color))
        binding.timeBar.setProgressCompat(((left * 1000) / budget).toInt(), true)
    }

    private fun allowedApps(session: SessionState, emergencyOnly: Boolean): List<AppEntry> {
        val pm = packageManager
        val emergency = EmergencyApps.resolve(applicationContext)
        val restricted = KidsPolicy.parse(session.activeProfile?.blockedPackagesJson)
        val curfews = PolicyStore(applicationContext)

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { pkg ->
                when {
                    pkg == packageName -> false
                    pkg in emergency -> true
                    emergencyOnly -> false
                    pkg in KidsPolicy.ALWAYS_BLOCKED -> false
                    pkg in restricted -> false
                    curfews.isCurrentlyCurfewed(pkg, session.activeRole) -> false
                    else -> true
                }
            }
            .mapNotNull { pkg ->
                val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                val info = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return@mapNotNull null }
                AppEntry(pkg, pm.getApplicationLabel(info).toString(), try { pm.getApplicationIcon(info) } catch (e: Exception) { null }, launch)
            }
            .sortedWith(compareByDescending<AppEntry> { it.pkg in emergency }.thenBy { it.label.lowercase() })
    }

    // ------------------------------------------------------------------ kiosk

    private fun isDeviceOwner() =
        (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(packageName)

    private fun inLockTask() =
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun setKiosk(on: Boolean, allowedPackages: Set<String>) {
        kiosk = on
        stayInKiosk.isEnabled = on
        binding.btnSwitchUser.text = if (on) "Grown-ups" else "Switch user"
        binding.btnSwitchUser.setIconResource(if (on) R.drawable.ic_lock else R.drawable.ic_switch_user)
        try {
            if (on) {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, SmartGuardDeviceAdminReceiver::class.java)
                // Only SmartGuard and the child's allowed apps may run while pinned.
                dpm.setLockTaskPackages(admin, (allowedPackages + packageName).toTypedArray())
                // Keep the lock screen (screen-on face checks) and the power menu (emergency calls).
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
                if (!inLockTask()) {
                    startLockTask()
                    SgLog.i(TAG, "Kiosk mode on (${allowedPackages.size} apps)")
                }
            } else if (inLockTask()) {
                stopLockTask()
                SgLog.i(TAG, "Kiosk mode off")
            }
        } catch (e: Exception) {
            SgLog.w(TAG, "Kiosk change failed: ${e.message}")
        }
    }

    /** "Grown-ups": a parent's face leaves kiosk mode and switches to that parent. */
    private fun leaveKiosk() {
        parentAuth.requireWithParent("Leave kiosk mode") { parentId ->
            lifecycleScope.launch {
                val parent = withContext(Dispatchers.IO) { SmartGuardApp.instance.profileRepository.getProfileById(parentId) }
                if (inLockTask()) stopLockTask()
                if (parent != null) SessionController.applyConfirmedMatch(parent)
            }
        }
    }

    // ------------------------------------------------------------------ notice

    /** "Instagram isn't available" style notice that fades away after a few seconds. */
    private fun showNotice(intent: Intent?) {
        val pkg = intent?.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: return
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "That app"
        }
        binding.tvNotice.text = "$label isn't available"
        binding.tvNoticeDetail.text = reason
        binding.noticeCard.alpha = 0f
        binding.noticeCard.visibility = View.VISIBLE
        binding.noticeCard.animate().alpha(1f).setDuration(200).start()
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch {
            delay(5000)
            binding.noticeCard.animate().alpha(0f).setDuration(300)
                .withEndAction { binding.noticeCard.visibility = View.GONE }.start()
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.VH>() {
        private var items: List<AppEntry> = emptyList()

        fun submit(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemLauncherAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = items[position]
            holder.binding.appIcon.setImageDrawable(app.icon)
            holder.binding.appLabel.text = app.label
            holder.binding.root.setOnClickListener {
                startActivity(Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        inner class VH(val binding: ItemLauncherAppBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
