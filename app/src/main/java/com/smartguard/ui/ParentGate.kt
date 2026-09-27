package com.smartguard.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smartguard.SmartGuardApp
import com.smartguard.policy.UserRole
import kotlinx.coroutines.launch

/**
 * Parent-only screens call this in onCreate. If the session stops being a parent's while the
 * screen is open (e.g. the phone is handed to a child and the unlock check switches to Child mode),
 * the screen closes immediately so no admin option stays reachable.
 */
fun AppCompatActivity.finishWhenParentLeaves() {
    lifecycleScope.launch {
        SmartGuardApp.instance.sessionState.collect { session ->
            if (session.activeRole != UserRole.ADULT && !isFinishing) finish()
        }
    }
}
