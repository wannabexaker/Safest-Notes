package com.tezas.safestnotes.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager

/**
 * Tracks when the app goes to background and locks the vault once the
 * configured idle timeout elapses. Register on
 * [androidx.lifecycle.ProcessLifecycleOwner] from Application.onCreate.
 */
class AutoLockManager(
    private val appContext: Context,
    private val securityManager: SecurityManager
) : DefaultLifecycleObserver {

    companion object {
        const val PREF_AUTO_LOCK_MINUTES = "auto_lock_timeout"
        // Special values: "-1" = never.
        const val DEFAULT_MINUTES = "5"
    }

    private var backgroundedAt: Long = 0L

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt == 0L) return
        val elapsed = System.currentTimeMillis() - backgroundedAt
        backgroundedAt = 0L
        val minutes = readMinutes()
        if (minutes < 0) return // never
        val timeoutMs = minutes * 60_000L
        if (elapsed >= timeoutMs) {
            securityManager.lock()
        }
    }

    private fun readMinutes(): Int {
        val raw = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_AUTO_LOCK_MINUTES, DEFAULT_MINUTES) ?: DEFAULT_MINUTES
        return raw.toIntOrNull() ?: 5
    }
}
