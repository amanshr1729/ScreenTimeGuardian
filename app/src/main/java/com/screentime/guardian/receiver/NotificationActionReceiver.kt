package com.screentime.guardian.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.screentime.guardian.service.ScreenTimeService

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceAction = when (intent.action) {
            ACTION_TAKE_BREAK -> ScreenTimeService.ACTION_TAKE_BREAK
            ACTION_SNOOZE_5 -> ScreenTimeService.ACTION_SNOOZE_5
            ACTION_SNOOZE_10 -> ScreenTimeService.ACTION_SNOOZE_10
            ACTION_SNOOZE_15 -> ScreenTimeService.ACTION_SNOOZE_15
            else -> return
        }
        val serviceIntent = Intent(context, ScreenTimeService::class.java).apply {
            action = serviceAction
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_TAKE_BREAK = "com.screentime.guardian.NOTIF_TAKE_BREAK"
        const val ACTION_SNOOZE_5 = "com.screentime.guardian.NOTIF_SNOOZE_5"
        const val ACTION_SNOOZE_10 = "com.screentime.guardian.NOTIF_SNOOZE_10"
        const val ACTION_SNOOZE_15 = "com.screentime.guardian.NOTIF_SNOOZE_15"
    }
}
