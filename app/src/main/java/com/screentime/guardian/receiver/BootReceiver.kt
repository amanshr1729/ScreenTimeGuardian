package com.screentime.guardian.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.screentime.guardian.data.GuardianPrefs
import com.screentime.guardian.service.ScreenTimeService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = GuardianPrefs(context)
            if (prefs.isRunning) {
                val serviceIntent = Intent(context, ScreenTimeService::class.java).apply {
                    action = ScreenTimeService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
