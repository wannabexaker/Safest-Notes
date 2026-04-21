package com.tezas.safestnotes.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager
import com.tezas.safestnotes.security.AutoLockManager
import com.tezas.safestnotes.security.SecurityManager

class SafestNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Respect user's dark-mode preference; default = true (Obsidian dark)
        val dark = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Security: initialise singleton and install the auto-lock observer.
        val securityManager = SecurityManager.init(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            AutoLockManager(applicationContext, securityManager)
        )
    }
}
