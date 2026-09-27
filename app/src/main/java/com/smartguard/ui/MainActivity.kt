package com.smartguard.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.accessibility.AccessibilityGuard
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.databinding.ActivityMainBinding
import com.smartguard.databinding.ItemSettingRowBinding
import com.smartguard.deviceowner.DeviceOwnerManager
import com.smartguard.enrollment.EnrollmentActivity
import com.smartguard.handover.SmartGuardMonitorService
import com.smartguard.handover.VerificationLauncher
import com.smartguard.policy.EmergencyApps
import com.smartguard.policy.ParentPinStore
import com.smartguard.policy.SessionController
import com.smartguard.policy.SessionState
import com.smartguard.policy.UsageStore
import com.smartguard.policy.UserRole
import com.smartguard.recognition.embedding.FaceEmbedder
import com.smartguard.ui.manage.ProfileEditorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home. Shows exactly one of three views:
 *  - Child / teen / not verified: who's using the phone, a time-left ring, emergency apps, Switch user.
 *  - First-time setup: one button to add the parent's face.
 *  - Parent: Home / Family / Protection tabs. Admin options exist ONLY here.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var usage: UsageStore
    private lateinit var familyTodayAdapter: FamilyAdapter
    private lateinit var familyAdapter: FamilyAdapter

    private var profiles: List<ProfileEntity> = emptyList()

    /** Every settings change needs a fresh parent face check. */
    private val parentAuth = ParentAuthGate(this)
    private var emergencyShown = false

    /** Set when we send the parent into a Settings page; checked when they come back. */
    private var returningFromSettings = false

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Camera access is needed to recognise faces", Toast.LENGTH_LONG).show()
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceOwnerManager = DeviceOwnerManager(this)
        usage = UsageStore(applicationContext)

        familyTodayAdapter = FamilyAdapter(usage) { openMember(it) }
        familyAdapter = FamilyAdapter(usage) { openMember(it) }
        binding.rvFamilyToday.layoutManager = LinearLayoutManager(this)
        binding.rvFamilyToday.adapter = familyTodayAdapter
        binding.rvFamily.layoutManager = LinearLayoutManager(this)
        binding.rvFamily.adapter = familyAdapter

        setupQuickActions()
        setupListeners()
        checkPermissions()
        observeSessionAndProfiles()
        startMonitorService()
        showRecognitionModelStatus()
    }

    override fun onResume() {
        super.onResume()
        updateProtection()
        AccessibilityGuard.ensureEnabled(applicationContext)
        promptToRestoreAccessibilityIfNeeded()
    }

    // ------------------------------------------------------------------ rendering

    private fun observeSessionAndProfiles() {
        val app = SmartGuardApp.instance
        lifecycleScope.launch {
            app.sessionState.combine(app.profileRepository.getAllProfilesFlow()) { s, p -> s to p }
                .collectLatest { (session, list) ->
                    profiles = list
                    render(session)
                }
        }
    }

    private fun render(session: SessionState) {
        val firstTime = profiles.isEmpty()
        val isParent = session.activeRole == UserRole.ADULT
        binding.setupView.visibility = if (firstTime) View.VISIBLE else View.GONE
        binding.parentView.visibility = if (!firstTime && isParent) View.VISIBLE else View.GONE
        binding.kidView.visibility = if (!firstTime && !isParent) View.VISIBLE else View.GONE

        when {
            firstTime -> Unit
            isParent -> renderParent(session)
            else -> renderKid(session)
        }
    }

    private fun renderKid(session: SessionState) {
        val profile = session.activeProfile
        val role = session.activeRole
        binding.tvKidGreeting.text = if (profile != null && role != UserRole.UNKNOWN_RESTRICTIVE) {
            "Hi, ${profile.name.substringBefore(" (")}"
        } else {
            "Who's using the phone?"
        }

        val (label, fg, bg) = when {
            (profile?.id ?: 0) < 0 && role != UserRole.UNKNOWN_RESTRICTIVE -> Triple("Guest mode", R.color.teen_mode_fg, R.color.teen_mode_bg)
            role == UserRole.CHILD -> Triple("Child mode", R.color.child_mode_fg, R.color.child_mode_bg)
            role == UserRole.TEEN -> Triple("Teen mode", R.color.teen_mode_fg, R.color.teen_mode_bg)
            else -> Triple("Locked until someone verifies", R.color.restrictive_mode_fg, R.color.restrictive_mode_bg)
        }
        binding.tvKidMode.text = label
        binding.tvKidMode.setTextColor(ContextCompat.getColor(this, fg))
        binding.tvKidMode.background.mutate().setTint(ContextCompat.getColor(this, bg))

        if (role == UserRole.UNKNOWN_RESTRICTIVE || session.isSessionLocked) {
            binding.ringKid.setProgressCompat(0, false)
            binding.tvKidMinutes.text = "–"
            binding.tvKidMinutesLabel.text = "Tap Switch user"
        } else {
            val budgetSec = (session.screenTimeBudgetMinutes * 60L).coerceAtLeast(1L)
            val left = session.remainingScreenTimeSeconds.coerceIn(0L, budgetSec)
            binding.ringKid.setProgressCompat(((left * 1000) / budgetSec).toInt(), true)
            val ringColor = when {
                left == 0L -> R.color.accent_rose
                left <= 600L -> R.color.accent_amber
                else -> R.color.primary
            }
            binding.ringKid.setIndicatorColor(ContextCompat.getColor(this, ringColor))
            if (left == 0L) {
                binding.tvKidMinutes.text = "0"
                binding.tvKidMinutesLabel.text = "Time's up for today"
            } else {
                binding.tvKidMinutes.text = ((left + 59) / 60).toString()
                binding.tvKidMinutesLabel.text = "min left today"
            }
        }
        if (!emergencyShown) showEmergencyApps()
    }

    /** Real icons of this phone's emergency apps; tap to open. */
    private fun showEmergencyApps() {
        emergencyShown = true
        lifecycleScope.launch {
            val pm = packageManager
            val apps = withContext(Dispatchers.IO) {
                EmergencyApps.resolve(applicationContext)
                    .mapNotNull { pkg -> pm.getLaunchIntentForPackage(pkg)?.let { pkg to it } }
                    .take(5)
            }
            val row = binding.rowEmergencyApps
            row.removeAllViews()
            val size = (52 * resources.displayMetrics.density).toInt()
            val margin = (10 * resources.displayMetrics.density).toInt()
            apps.forEach { (pkg, launch) ->
                val icon = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(margin, 0, margin, 0) }
                    contentDescription = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) } catch (e: Exception) { pkg }
                    setImageDrawable(try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null })
                    setOnClickListener { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                row.addView(icon)
            }
        }
    }

    private fun renderParent(session: SessionState) {
        val me = session.activeProfile
        binding.tvGreeting.text = "Hi, ${me?.name ?: "there"}"
        binding.tvActiveUser.text = me?.name?.let { "$it · parent mode" } ?: "Parent mode"
        binding.tvScreenTime.text = "Full access · tap Hand over to lend the phone with chosen apps"

        val kids = profiles.filter { it.role.equals("CHILD", true) || it.role.equals("TEEN", true) }
        familyTodayAdapter.submit(kids, me?.id)
        binding.tvFamilyTodayEmpty.visibility = if (kids.isEmpty()) View.VISIBLE else View.GONE
        familyAdapter.submit(profiles, me?.id)
    }

    // ------------------------------------------------------------------ parent actions

    private fun setupQuickActions() {
        fun tile(t: com.smartguard.databinding.ItemQuickActionBinding, icon: Int, label: String, hint: String, onTap: () -> Unit) {
            t.qaIcon.setImageResource(icon)
            t.qaLabel.text = label
            t.qaHint.text = hint
            t.root.setOnClickListener { onTap() }
        }
        tile(binding.qaAddFace, R.drawable.ic_person_add, "Add face", "Child, teen or parent") { onEnrollClicked() }
        tile(binding.qaFamily, R.drawable.ic_family, "Family rules", "Time, apps, curfews") { selectTab(R.id.tab_family) }
        tile(binding.qaAssistant, R.drawable.ic_chat, "Ask assistant", "Rules in plain English") {
            requireParent { startActivity(Intent(this, PolicyAssistantActivity::class.java)) }
        }
        tile(binding.qaHandOver, R.drawable.ic_lock, "Hand over", "Pick apps to lend") { handOver() }
    }

    private fun setupListeners() {
        binding.bottomNav.setOnItemSelectedListener { showTab(it.itemId); true }
        binding.chipProtection.setOnClickListener { selectTab(R.id.tab_protection) }

        binding.btnSwitchUser.setOnClickListener { VerificationLauncher.launchVisibleCheck(this, force = true) }
        binding.btnMyApps.setOnClickListener { startActivity(KidLauncherActivity.intent(this, null, null)) }
        binding.btnParentPin.setOnClickListener { showParentPinDialog() }
        binding.btnSetupEnroll.setOnClickListener { startActivity(Intent(this, EnrollmentActivity::class.java)) }

        binding.btnAddMember.setOnClickListener { onEnrollClicked() }
        binding.btnAssistantFamily.setOnClickListener {
            requireParent { startActivity(Intent(this, PolicyAssistantActivity::class.java)) }
        }
        binding.btnChangePin.setOnClickListener {
            requireParent { parentAuth.require("Change the parent PIN") { showChangePinDialog() } }
        }

        binding.btnAdbGuide.setOnClickListener { showAdbProvisioningDialog() }
        binding.btnApplyAntiBypass.setOnClickListener {
            parentAuth.require("Turn on the lockdown") {
                val ok = deviceOwnerManager.applyAntiBypassLockdown()
                Toast.makeText(this, if (ok) "Lockdown on" else "Couldn't apply lockdown", Toast.LENGTH_SHORT).show()
                updateProtection()
            }
        }
        binding.btnReleaseDeviceOwner.setOnClickListener { requireParent { confirmReleaseDeviceOwner() } }
    }

    /** Programmatic tab switch (quick actions, header chip). The nav listener then calls [showTab]. */
    private fun selectTab(id: Int) {
        if (binding.bottomNav.selectedItemId != id) binding.bottomNav.selectedItemId = id else showTab(id)
    }

    /** Only swaps the visible tab. Must never touch bottomNav (that re-fires the listener -> infinite loop). */
    private fun showTab(id: Int) {
        binding.tabHome.visibility = if (id == R.id.tab_home) View.VISIBLE else View.GONE
        binding.tabFamily.visibility = if (id == R.id.tab_family) View.VISIBLE else View.GONE
        binding.tabProtection.visibility = if (id == R.id.tab_protection) View.VISIBLE else View.GONE
        if (id == R.id.tab_protection) updateProtection()
    }

    private fun openMember(p: ProfileEntity) {
        requireParent {
            if (UserRole.fromString(p.role) == UserRole.ADULT) {
                AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setItems(arrayOf("Update face", "Delete")) { _, which ->
                        if (which == 0) {
                            parentAuth.require("Update ${p.name}'s face") {
                                startActivity(Intent(this, EnrollmentActivity::class.java).putExtra(EnrollmentActivity.EXTRA_PROFILE_ID, p.id))
                            }
                        } else {
                            confirmDeleteParent(p)
                        }
                    }
                    .show()
            } else {
                startActivity(Intent(this, ProfileEditorActivity::class.java).putExtra(ProfileEditorActivity.EXTRA_PROFILE_ID, p.id))
            }
        }
    }

    private fun confirmDeleteParent(p: ProfileEntity) {
        val parents = profiles.count { UserRole.fromString(it.role) == UserRole.ADULT }
        if (parents <= 1) {
            Toast.makeText(this, "Add another parent before deleting the last one", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("Their face data is removed from this phone.")
            .setPositiveButton("Delete") { _, _ ->
                parentAuth.require("Delete ${p.name}") {
                    lifecycleScope.launch { SmartGuardApp.instance.profileRepository.deleteProfile(p) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Lend the phone: pick the apps (every time), then it's pinned to them until a parent ends it. */
    private fun handOver() {
        startActivity(Intent(this, com.smartguard.ui.handover.HandoverPickerActivity::class.java))
    }

    private fun onEnrollClicked() {
        if (profiles.isEmpty()) {
            startActivity(Intent(this, EnrollmentActivity::class.java)) // first-time setup
        } else {
            requireParent { parentAuth.require("Add a new face") { startActivity(Intent(this, EnrollmentActivity::class.java)) } }
        }
    }

    /** Runs [action] if a parent is verified; otherwise offers to verify first. */
    private fun requireParent(action: () -> Unit) {
        if (SmartGuardApp.instance.sessionState.value.activeRole == UserRole.ADULT) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Parents only")
            .setMessage("Verify your face as the parent first.")
            .setPositiveButton("Verify") { _, _ -> VerificationLauncher.launchVisibleCheck(this, force = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pinInput(hint: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = hint
    }

    /** Parent fallback when face recognition isn't possible (e.g. dark room). */
    private fun showParentPinDialog() {
        val input = pinInput("Parent PIN")
        AlertDialog.Builder(this)
            .setTitle("Parent unlock")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (!ParentPinStore(this).verify(input.text.toString().trim())) {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val owner = withContext(Dispatchers.IO) { SmartGuardApp.instance.profileRepository.getOwnerProfile() }
                    if (owner != null) SessionController.applyConfirmedMatch(owner)
                    else SmartGuardApp.instance.updateActiveRole(UserRole.ADULT)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val input = pinInput("New PIN (4–8 digits)")
        AlertDialog.Builder(this)
            .setTitle("Change parent PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val ok = ParentPinStore(this).setPin(input.text.toString().trim())
                Toast.makeText(this, if (ok) "PIN updated" else "Use 4 to 8 digits", Toast.LENGTH_SHORT).show()
                updateProtection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ protection

    private fun row(r: ItemSettingRowBinding, ok: Boolean, title: String, subtitle: String, action: String? = null, onAction: (() -> Unit)? = null) {
        r.rowIcon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning)
        r.rowIcon.setColorFilter(ContextCompat.getColor(this, if (ok) R.color.accent_emerald else R.color.accent_amber))
        r.rowTitle.text = title
        r.rowSubtitle.text = subtitle
        if (action != null && onAction != null) {
            r.rowAction.visibility = View.VISIBLE
            r.rowAction.text = action
            r.rowAction.setOnClickListener { onAction() }
        } else {
            r.rowAction.visibility = View.GONE
        }
    }

    private fun updateProtection() {
        val a11y = AccessibilityGuard.isEnabled(this)
        val background = AccessibilityGuard.isBackgroundAllowed(this) || AccessibilityGuard.isBatteryExempt(this)
        val selfHeal = AccessibilityGuard.canSelfHeal(this)
        val owner = deviceOwnerManager.isDeviceOwner()
        val lockdown = deviceOwnerManager.isLockdownActive()
        val pinDefault = ParentPinStore(this).isDefault

        row(
            binding.rowA11y, a11y, "App rules and unlock checks",
            if (a11y) "On" else "Off. vivo turns this off whenever Settings opens.",
            if (a11y) null else "Turn on"
        ) { openSettingsExpectingA11yLoss(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        row(
            binding.rowBackground, background, "Runs in the background",
            if (background) "Allowed" else "Needed so vivo doesn't stop SmartGuard",
            if (background) null else "Allow"
        ) { requestBackgroundRunning() }

        row(
            binding.rowSelfHeal, selfHeal, "Turns itself back on",
            if (selfHeal) "On" else "Run once on the PC:\nadb shell pm grant com.smartguard android.permission.WRITE_SECURE_SETTINGS"
        )

        row(
            binding.rowDeviceOwner, lockdown,
            "Can't be removed by a child",
            when {
                lockdown -> "Lockdown on: no uninstall, factory reset or safe mode. Restricted apps are paused by Android for kids; installs, force-stop and clock changes are blocked."
                owner -> "Device Owner is set: restricted apps are paused by Android for kids. Apply the lockdown below."
                else -> "Optional: needs Device Owner (see the guide below)"
            }
        )
        binding.btnApplyAntiBypass.visibility = if (owner && !lockdown) View.VISIBLE else View.GONE
        binding.btnReleaseDeviceOwner.visibility = if (owner) View.VISIBLE else View.GONE
        binding.btnAdbGuide.visibility = if (owner) View.GONE else View.VISIBLE

        // Header chip: one glance says whether protection is working.
        val protectedNow = a11y && background
        binding.chipProtection.text = if (protectedNow) "Protected" else "Needs attention"
        binding.chipProtection.setTextColor(ContextCompat.getColor(this, if (protectedNow) R.color.accent_emerald else R.color.accent_amber))
        binding.chipProtection.background.mutate()
            .setTint(ContextCompat.getColor(this, if (protectedNow) R.color.success_container else R.color.warning_container))
        binding.btnChangePin.text = if (pinDefault) "Set a parent PIN (still the default)" else "Change parent PIN"
    }

    /**
     * On vivo, every Settings screen makes i Manager switch SmartGuard's accessibility off. If vivo
     * background running is already allowed there's nothing to change; otherwise warn, open vivo's
     * page, and prompt to re-enable accessibility on return.
     */
    private fun requestBackgroundRunning() {
        if (AccessibilityGuard.isBackgroundAllowed(this)) {
            Toast.makeText(this, "Background running is already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow background running")
            .setMessage(
                "On the next screen, allow SmartGuard to run in the background.\n\n" +
                    "vivo turns SmartGuard's accessibility off whenever Settings opens. When you come back, " +
                    "SmartGuard takes you straight to switch it on again."
            )
            .setPositiveButton("Continue") { _, _ ->
                openSettingsExpectingA11yLoss(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSettingsExpectingA11yLoss(intent: Intent) {
        returningFromSettings = true
        startActivity(intent)
    }

    private fun promptToRestoreAccessibilityIfNeeded() {
        if (!returningFromSettings) return
        returningFromSettings = false
        if (AccessibilityGuard.isEnabled(this)) return
        AlertDialog.Builder(this)
            .setTitle("Turn SmartGuard back on")
            .setMessage("vivo switched SmartGuard's accessibility off while Settings was open. Without it, unlock checks and app rules stop.")
            .setPositiveButton("Open accessibility") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun confirmReleaseDeviceOwner() {
        AlertDialog.Builder(this)
            .setTitle("Release Device Owner?")
            .setMessage("Removes the lockdown and makes SmartGuard an ordinary app that can be uninstalled normally. Face checks and app rules keep working.")
            .setPositiveButton("Release") { _, _ ->
                parentAuth.require("Release Device Owner") {
                    val ok = deviceOwnerManager.releaseDeviceOwner()
                    Toast.makeText(this, if (ok) "Device Owner released" else "Couldn't release Device Owner", Toast.LENGTH_LONG).show()
                    updateProtection()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAdbProvisioningDialog() {
        AlertDialog.Builder(this)
            .setTitle("Set up Device Owner")
            .setMessage(
                "Makes SmartGuard impossible for a child to uninstall, and blocks factory reset and safe mode.\n\n" +
                    "1. Connect the phone to a PC with USB debugging on.\n" +
                    "2. Make sure no accounts are signed in on the phone.\n" +
                    "3. Run:\n\nadb shell dpm set-device-owner com.smartguard/.deviceowner.SmartGuardDeviceAdminReceiver\n\n" +
                    "Undo any time with Release Device Owner here."
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    // ------------------------------------------------------------------ misc

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, SmartGuardMonitorService::class.java).apply { action = SmartGuardMonitorService.ACTION_START }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showRecognitionModelStatus() {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.Default) {
                val embedder = FaceEmbedder(applicationContext)
                val n = embedder.modelName
                embedder.close()
                n
            }
            binding.tvModelStatus.text = if (name != null) {
                "Face and age recognition run on this phone (${name.removeSuffix(".tflite")}). Nothing is uploaded."
            } else {
                "No face model bundled: recognition is weak"
            }
        }
    }
}
