package com.smartguard

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.smartguard.accessibility.AccessibilityGuard
import com.smartguard.data.local.AppDatabase
import com.smartguard.data.local.security.KeystoreCryptoManager
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.policy.SessionState
import com.smartguard.policy.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SmartGuardApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var cryptoManager: KeystoreCryptoManager
        private set

    lateinit var profileRepository: ProfileRepository
        private set

    private val _sessionState = MutableStateFlow(SessionState())
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.smartguard.util.SgLog.init(this)

        database = AppDatabase.getInstance(this)
        cryptoManager = KeystoreCryptoManager()
        profileRepository = ProfileRepository(
            profileDao = database.profileDao(),
            embeddingDao = database.embeddingDao(),
            cryptoManager = cryptoManager
        )
        watchAccessibilitySetting()
        // Device Owner: pause restricted apps / child phone rules whenever the session changes.
        com.smartguard.deviceowner.DeviceRules.start(this)
    }

    /**
     * vivo's i Manager removes third-party accessibility services every time the Settings app opens,
     * and vivo's freezer may stop our background service. This observer lives in the application
     * process itself: the instant the enabled-services list changes, SmartGuard re-adds itself
     * (when self-heal is granted) before the process can be frozen.
     */
    private fun watchAccessibilitySetting() {
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    AccessibilityGuard.ensureEnabled(this@SmartGuardApp)
                }
            }
        )
    }

    fun updateSessionState(newState: SessionState) {
        _sessionState.value = newState
    }

    fun updateActiveRole(role: UserRole) {
        _sessionState.value = _sessionState.value.copy(activeRole = role)
    }

    companion object {
        lateinit var instance: SmartGuardApp
            private set
    }
}
