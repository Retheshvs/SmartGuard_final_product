package com.smartguard.ui.manage

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.data.local.entity.ProfileEntity
import com.smartguard.databinding.ActivityProfileEditorBinding
import com.smartguard.databinding.ItemAppToggleBinding
import com.smartguard.enrollment.EnrollmentActivity
import com.smartguard.policy.AppCategories
import com.smartguard.policy.DayScope
import com.smartguard.policy.EmergencyApps
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.KioskStore
import com.smartguard.policy.PolicyStore
import com.smartguard.policy.SessionController
import com.smartguard.policy.UsageStore
import com.smartguard.policy.UserRole
import com.smartguard.ui.ParentAuthGate
import com.smartguard.ui.finishWhenParentLeaves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Parent-only editor for one CHILD/TEEN profile. */
class ProfileEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        private val ROLES = arrayOf("CHILD", "TEEN")
        private const val BUDGET_MIN = 15
        private const val BUDGET_MAX = 480
        private const val BUDGET_STEP = 15
    }

    private data class AppItem(val pkg: String, val label: String, val icon: Drawable?, val category: String?)

    private lateinit var binding: ActivityProfileEditorBinding

    /** Every change here needs a fresh parent face check. */
    private val parentAuth = ParentAuthGate(this)
    private val repo get() = SmartGuardApp.instance.profileRepository
    private lateinit var usage: UsageStore
    private lateinit var policyStore: PolicyStore

    private var profile: ProfileEntity? = null
    /** Packages the parent restricted for this profile (switch ON = restricted). */
    private val restricted = mutableSetOf<String>()

    /** Calls, messages, camera, WhatsApp, safety apps: always available, can't be restricted. */
    private var emergency: Set<String> = emptySet()
    private var allApps: List<AppItem> = emptyList()
    private val appAdapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SmartGuardApp.instance.sessionState.value.activeRole != UserRole.ADULT) {
            Toast.makeText(this, "Parent gated.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        finishWhenParentLeaves()
        usage = UsageStore(applicationContext)
        policyStore = PolicyStore(applicationContext)

        binding.spinnerRole.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ROLES)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = appAdapter
        binding.sliderBudget.addOnChangeListener { _, value, _ -> binding.tvBudget.text = formatBudget(value.toInt()) }
        binding.etSearch.doAfterTextChanged { applyFilter() }
        binding.appFilter.setOnCheckedStateChangeListener { _, _ -> applyFilter() }
        binding.spinnerRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateKioskRow()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        binding.btnSave.setOnClickListener { parentAuth.require("Save ${profile?.name ?: "these"} rules") { save() } }
        binding.btnResetUsage.setOnClickListener { parentAuth.require("Reset today's screen time") { resetUsage() } }
        binding.btnReenroll.setOnClickListener {
            profile?.let {
                parentAuth.require("Update ${it.name}'s face") {
                    startActivity(Intent(this, EnrollmentActivity::class.java).putExtra(EnrollmentActivity.EXTRA_PROFILE_ID, it.id))
                }
            }
        }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnResetLearned.setOnClickListener {
            profile?.let {
                parentAuth.require("Forget what SmartGuard learned about ${it.name}'s face") {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { repo.clearLearnedEmbeddings(it.id) }
                        showFaceData(it)
                    }
                }
            }
        }

        load(intent.getLongExtra(EXTRA_PROFILE_ID, -1L))
    }

    private fun load(id: Long) {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { repo.getProfileById(id) }
            if (p == null) {
                Toast.makeText(this@ProfileEditorActivity, "Profile not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            profile = p
            binding.tvTitle.text = "Edit ${p.name}"
            binding.etName.setText(p.name)
            binding.spinnerRole.setSelection(ROLES.indexOfFirst { it.equals(p.role, true) }.coerceAtLeast(0))
            val budget = snapBudget(p.screenTimeBudgetMinutes)
            binding.sliderBudget.value = budget.toFloat()
            binding.tvBudget.text = formatBudget(budget)
            showUsage(p)
            showFaceData(p)
            renderCurfews(p.role)
            binding.switchKiosk.isChecked = KioskStore(applicationContext).isOn(p.id)
            updateKioskRow()

            restricted.clear()
            restricted += KidsPolicy.parse(p.blockedPackagesJson)

            emergency = withContext(Dispatchers.IO) { EmergencyApps.resolve(applicationContext) }
            restricted -= emergency
            allApps = withContext(Dispatchers.IO) { loadLaunchableApps() }
            binding.progressApps.visibility = View.GONE
            applyFilter()
        }
    }

    /** Kiosk mode is for children only, and needs Device Owner to pin the screen. */
    private fun updateKioskRow() {
        val isChild = ROLES[binding.spinnerRole.selectedItemPosition] == "CHILD"
        binding.rowKiosk.visibility = if (isChild) View.VISIBLE else View.GONE
        val owner = (getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).isDeviceOwnerApp(packageName)
        binding.tvKioskHint.text = if (owner) {
            "For young kids: keeps them in their own simple home screen. No notifications, no leaving it."
        } else {
            "Needs Device Owner (see the Protection tab)."
        }
        binding.switchKiosk.isEnabled = owner
    }

    /** "5 enrolled · 3 learned looks" — the profile grows with the child from confident checks. */
    private suspend fun showFaceData(p: ProfileEntity) {
        val s = withContext(Dispatchers.IO) { repo.faceDataSummary(p.id) }
        val learned = when (s.learned) {
            0 -> "nothing learned yet"
            1 -> "1 learned look"
            else -> "${s.learned} learned looks"
        }
        val last = s.lastLearnedAt?.let {
            " · last " + android.text.format.DateUtils.getRelativeTimeSpanString(
                it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            )
        }.orEmpty()
        binding.tvFaceData.text = "${s.enrolled} enrolled · $learned$last"
        binding.tvFaceDataHint.text = "SmartGuard adds a new look only after a near-certain, live match, so " +
            "${p.name}'s profile keeps up as they grow. The enrolled face is never changed."
        binding.btnResetLearned.visibility = if (s.learned > 0) View.VISIBLE else View.GONE
    }

    private fun showUsage(p: ProfileEntity) {
        val used = usage.usedSecondsToday(p.id) / 60
        binding.tvUsedToday.text = "Used today: $used min"
    }

    private fun loadLaunchableApps(): List<AppItem> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName && it.packageName !in KidsPolicy.ALWAYS_BLOCKED }
            .map { info ->
                AppItem(
                    pkg = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = try { pm.getApplicationIcon(info) } catch (e: Exception) { null },
                    category = AppCategories.categoryOf(info.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun applyFilter() {
        val q = binding.etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val mode = binding.appFilter.checkedChipId
        val filtered = allApps
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .filter {
                when (mode) {
                    R.id.chipAllowed -> it.pkg !in restricted
                    R.id.chipRestricted -> it.pkg in restricted
                    else -> true
                }
            }
            // Restricted apps first so the parent sees the current restrictions at a glance.
            .sortedWith(compareByDescending<AppItem> { it.pkg in restricted }.thenBy { it.label.lowercase() })
        appAdapter.submit(filtered)
        updateAllowedCount()
    }

    /** Pill labels carry live counts; the list itself isn't re-filtered on each toggle (no jumping rows). */
    private fun updateAllowedCount() {
        val restrictedCount = allApps.count { it.pkg in restricted }
        binding.chipAll.text = "All ${allApps.size}"
        binding.chipAllowed.text = "Allowed ${allApps.size - restrictedCount}"
        binding.chipRestricted.text = "Restricted $restrictedCount"
        binding.tvAllowedCount.text = "Settings, app stores and system tools are always blocked for children and teens. " +
            "Emergency apps are always allowed."
    }

    private fun renderCurfews(role: String) {
        val container = binding.containerCurfews
        container.removeAllViews()
        val rules = policyStore.curfewsForRole(role)
        if (rules.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No curfews. Add them with the Policy Assistant (e.g. \"no games after 9pm on school nights\")."
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 13f
            })
            return
        }
        rules.forEach { rule ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val days = when (rule.days) {
                DayScope.SCHOOL_NIGHTS -> "school nights"
                DayScope.WEEKENDS -> "weekends"
                DayScope.DAILY -> "every day"
            }
            row.addView(TextView(this).apply {
                text = "No ${rule.category} after %02d:00 · %s".format(rule.afterHour, days)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Remove"
                setTextColor(ContextCompat.getColor(context, R.color.accent_rose))
                setOnClickListener {
                    parentAuth.require("Remove a curfew") {
                        policyStore.removeCurfew(rule)
                        renderCurfews(role)
                    }
                }
            })
            container.addView(row)
        }
    }

    private fun save() {
        val p = profile ?: return
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, "Name can't be empty", Toast.LENGTH_SHORT).show()
            return
        }
        val newRole = ROLES[binding.spinnerRole.selectedItemPosition]
        KioskStore(applicationContext).set(p.id, binding.switchKiosk.isChecked && newRole == "CHILD")
        val updated = p.copy(
            name = name,
            role = newRole,
            screenTimeBudgetMinutes = binding.sliderBudget.value.toInt(),
            blockedPackagesJson = KidsPolicy.toJson(restricted)
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.updateProfile(updated) }
            refreshSessionIfActive(updated)
            Toast.makeText(this@ProfileEditorActivity, "Saved ${updated.name}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** If this profile is the one using the phone right now, apply the new rules immediately. */
    private fun refreshSessionIfActive(updated: ProfileEntity) {
        val app = SmartGuardApp.instance
        val session = app.sessionState.value
        if (session.activeProfile?.id != updated.id) return
        app.updateSessionState(
            session.copy(
                activeProfile = updated,
                activeRole = UserRole.fromString(updated.role),
                screenTimeBudgetMinutes = updated.screenTimeBudgetMinutes,
                remainingScreenTimeSeconds = usage.remainingSeconds(updated.id, updated.screenTimeBudgetMinutes)
            )
        )
    }

    private fun resetUsage() {
        val p = profile ?: return
        usage.resetToday(p.id)
        showUsage(p)
        refreshSessionIfActive(p)
        Toast.makeText(this, "Today's usage reset for ${p.name}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        val p = profile ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete ${p.name}?")
            .setMessage("This removes the profile and its encrypted face data from this phone.")
            .setPositiveButton("Delete") { _, _ -> parentAuth.require("Delete ${p.name}") { deleteProfile(p) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteProfile(p: ProfileEntity) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.deleteProfile(p) }
                    usage.resetToday(p.id)
                    if (SmartGuardApp.instance.sessionState.value.activeProfile?.id == p.id) {
                        SessionController.applyFailSafe("Active profile deleted")
                    }
                    Toast.makeText(this@ProfileEditorActivity, "Deleted ${p.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }
    }


    private fun snapBudget(minutes: Int): Int {
        val clamped = minutes.coerceIn(BUDGET_MIN, BUDGET_MAX)
        return (((clamped - BUDGET_MIN) / BUDGET_STEP.toFloat()).roundToInt() * BUDGET_STEP + BUDGET_MIN).coerceAtMost(BUDGET_MAX)
    }

    private fun formatBudget(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min per day"
            m == 0 -> "$h h per day"
            else -> "$h h $m min per day"
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        private var items: List<AppItem> = emptyList()

        fun submit(list: List<AppItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAppToggleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.binding
            b.ivIcon.setImageDrawable(item.icon)
            b.tvLabel.text = item.label
            val isEmergency = item.pkg in emergency
            b.tvCategory.text = if (isEmergency) "Emergency · always available" else item.category ?: item.pkg
            b.switchAllowed.setOnCheckedChangeListener(null)
            b.switchAllowed.isEnabled = !isEmergency
            b.switchAllowed.isChecked = !isEmergency && item.pkg in restricted
            b.switchAllowed.setOnCheckedChangeListener { _, checked ->
                if (checked) restricted += item.pkg else restricted -= item.pkg
                updateAllowedCount()
            }
            b.root.setOnClickListener { if (!isEmergency) b.switchAllowed.toggle() }
        }

        inner class VH(val binding: ItemAppToggleBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
