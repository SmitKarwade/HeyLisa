package com.example.heylisa.application

import android.app.Application
import com.example.heylisa.util.AppStateObserver

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStateObserver.registerObserver()
    }
}
