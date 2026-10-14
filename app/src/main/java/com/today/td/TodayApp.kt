package com.today.td

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class TodayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        RootShell.init(this)
    }
}
