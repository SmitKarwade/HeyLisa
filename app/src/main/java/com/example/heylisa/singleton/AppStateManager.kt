package com.example.heylisa.util

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object AppStateObserver : DefaultLifecycleObserver {
    var isAppInForeground: Boolean = false
        private set

    override fun onStart(owner: LifecycleOwner) {
        isAppInForeground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        isAppInForeground = false
    }

    fun registerObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
}
