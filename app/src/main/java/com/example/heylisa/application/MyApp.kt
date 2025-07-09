package com.example.heylisa.application

import android.app.Application
import android.util.Log
import com.example.heylisa.util.AppStateObserver
import android.app.ActivityManager


class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStateObserver.registerObserver()
    }
}
