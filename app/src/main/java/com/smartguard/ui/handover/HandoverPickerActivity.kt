package com.smartguard.ui.handover

import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.smartguard.R
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityHandoverBinding
import com.smartguard.handover.HandoverSession
import com.smartguard.policy.KidsPolicy
import com.smartguard.policy.UserRole
import com.smartguard.ui.padForSystemBars
import com.smartguard.util.SgLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Hand over": the parent taps the apps the other person may use, on a screen that looks like the
 * phone's home screen. Nothing is pre-selected — it asks every time. Settings, app stores and system
 * tools are never offered (they would let someone escape the kiosk).
 */
class HandoverPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHandoverBinding
    private val adapter = HomeAppsAdapter(selectable = true) { updateSelection(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only a parent can lend the phone out.
        if (SmartGuardApp.instance.sessionState.value.activeRole != UserRole.ADULT) {
            finish()
            return
        }
        binding = ActivityHandoverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()

        binding.tvTitle.text = "Hand over"
        binding.rvApps.layoutManager = GridLayoutManager(this, 4)
        binding.rvApps.adapter = adapter
        binding.btnSecondary.text = "Cancel"
        binding.btnSecondary.setOnClickListener { finish() }
        binding.btnPrimary.setIconResource(R.drawable.ic_lock)
        binding.btnPrimary.setOnClickListener { start() }
        updateSelection(emptySet())

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadHomeApps(applicationContext, exclude = KidsPolicy.ALWAYS_BLOCKED) }
            adapter.submit(apps)
        }
    }

    private fun updateSelection(selected: Set<String>) {
        val n = selected.size
        binding.tvSubtitle.text = when (n) {
            0 -> "Tap the apps they can use"
            1 -> "1 app selected"
            else -> "$n apps selected"
        }
        binding.btnPrimary.text = if (n == 0) "Hand over" else "Hand over · $n"
        binding.btnPrimary.isEnabled = n > 0
        binding.btnPrimary.alpha = if (n > 0) 1f else 0.5f
    }

    private fun start() {
        val apps = adapter.selection.toSet()
        if (apps.isEmpty()) return
        val parent = SmartGuardApp.instance.sessionState.value.activeProfile?.name
        HandoverSession.start(applicationContext, apps, parent)
        SgLog.i("SG-Handover", "Handed over by ${parent ?: "parent"} with ${apps.size} apps: $apps")
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        startActivity(HandoverHomeActivity.intent(this))
        finish()
    }
}
