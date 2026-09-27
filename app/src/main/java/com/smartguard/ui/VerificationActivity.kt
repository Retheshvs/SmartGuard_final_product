package com.smartguard.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.smartguard.SmartGuardApp
import com.smartguard.databinding.ActivityVerificationBinding

/**
 * Verification & Unlock Activity.
 * Modern Keyguard Dismissal API design:
 * Uses KeyguardManager.requestDismissKeyguard() with callbacks. Only clears app internal 'locked' state
 * inside onDismissSucceeded() — never optimistically before.
 */
class VerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerificationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        val sessionState = SmartGuardApp.instance.sessionState.value
        binding.tvProfileName.text = sessionState.activeProfile?.name ?: "Unknown User"
        binding.tvActiveRole.text = "Role: ${sessionState.activeRole.name}"

        binding.btnUnlockKeyguard.setOnClickListener {
            requestDismissKeyguardModern()
        }
    }

    private fun requestDismissKeyguardModern() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    // CRITICAL: Only clear internal locked state AFTER dismissal succeeds!
                    val current = SmartGuardApp.instance.sessionState.value
                    SmartGuardApp.instance.updateSessionState(current.copy(isSessionLocked = false))
                    Toast.makeText(applicationContext, "Device Unlocked Successfully", Toast.LENGTH_SHORT).show()
                    finish()
                }

                override fun onDismissError() {
                    super.onDismissError()
                    Toast.makeText(applicationContext, "Unlock Failed", Toast.LENGTH_SHORT).show()
                }

                override fun onDismissCancelled() {
                    super.onDismissCancelled()
                    Toast.makeText(applicationContext, "Unlock Cancelled", Toast.LENGTH_SHORT).show()
                }
            })
        } else {
            // Legacy keyguard fallback
            val current = SmartGuardApp.instance.sessionState.value
            SmartGuardApp.instance.updateSessionState(current.copy(isSessionLocked = false))
            finish()
        }
    }
}
