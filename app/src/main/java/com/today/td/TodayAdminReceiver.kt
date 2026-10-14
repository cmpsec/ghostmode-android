package com.today.td

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class TodayAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        RootShell.invalidate()
    }

    companion object {
        fun component(context: Context) = ComponentName(context, TodayAdminReceiver::class.java)
    }
}
