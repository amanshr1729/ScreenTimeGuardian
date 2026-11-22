package com.screentime.guardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.screentime.guardian.MainActivity
import com.screentime.guardian.R
import com.screentime.guardian.data.GuardianPrefs
import com.screentime.guardian.receiver.NotificationActionReceiver
import com.screentime.guardian.util.TimeFormatter

class ScreenTimeService : Service() {

    private lateinit var prefs: GuardianPrefs
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var secondsSinceLastEscalation = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!prefs.isRunning) return

            if (isScreenOn) {
                prefs.todayScreenSeconds += 1L

                if (prefs.snoozeRemainingSeconds > 0L) {
                    prefs.snoozeRemainingSeconds -= 1L
                    if (prefs.snoozeRemainingSeconds == 0L) {
                        triggerBreakReminder(isEscalation = false)
                    }
                } else if (!prefs.isBreakDue) {
                    prefs.elapsedSessionSeconds += 1L
                    if (prefs.elapsedSessionSeconds >= prefs.targetIntervalSeconds) {
                        triggerBreakReminder(isEscalation = false)
                    }
                } else if (prefs.escalationEnabled) {
                    secondsSinceLastEscalation += 1L
                    if (secondsSinceLastEscalation >= ESCALATION_INTERVAL_SECONDS) {
                        secondsSinceLastEscalation = 0L
                        val nextCount = prefs.escalationCount + 1
                        prefs.escalationCount = nextCount
                        if (nextCount >= MAX_ESCALATIONS_BEFORE_STREAK_RESET) {
                            prefs.currentStreak = 0
                        }
                        if (nextCount <= MAX_ESCALATIONS_BEFORE_STREAK_RESET) {
                            triggerBreakReminder(isEscalation = true)
                        }
                    }
                }
            }

            updateOngoingNotification()
            broadcastStateUpdate()
            handler.postDelayed(this, 1000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    prefs.lastScreenOffTimestampMs = System.currentTimeMillis()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    val offAt = prefs.lastScreenOffTimestampMs
                    if (offAt > 0L) {
                        val offDurationSec = (System.currentTimeMillis() - offAt) / 1000L
                        prefs.lastScreenOffTimestampMs = 0L
                        if (offDurationSec >= NATURAL_BREAK_SECONDS &&
                            (prefs.isBreakDue || prefs.elapsedSessionSeconds >= 600L)
                        ) {
                            prefs.recordBreakTaken()
                            secondsSinceLastEscalation = 0L
                            notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                            if (prefs.toastEnabled) {
                                showToast("Welcome back! Natural break logged (Streak: ${prefs.currentStreak})")
                            }
                        }
                    }
                    broadcastStateUpdate()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardianPrefs(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        createNotificationChannels()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                prefs.isRunning = true
                startForegroundCompat()
                restartTicker()
            }
            ACTION_PAUSE -> {
                prefs.isRunning = false
                handler.removeCallbacks(tickRunnable)
                notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                broadcastStateUpdate()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESET -> {
                prefs.resetSessionOnly()
                secondsSinceLastEscalation = 0L
                notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                if (prefs.isRunning) {
                    startForegroundCompat()
                    restartTicker()
                }
                broadcastStateUpdate()
            }
            ACTION_TAKE_BREAK -> {
                startForegroundCompat()
                prefs.recordBreakTaken()
                secondsSinceLastEscalation = 0L
                notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                showToast("Break logged! Discipline Streak: ${prefs.currentStreak}")
                broadcastStateUpdate()
                if (prefs.isRunning) {
                    restartTicker()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_SNOOZE_5 -> handleSnooze(5)
            ACTION_SNOOZE_10 -> handleSnooze(10)
            ACTION_SNOOZE_15 -> handleSnooze(15)
            else -> {
                if (prefs.isRunning) {
                    startForegroundCompat()
                    restartTicker()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun handleSnooze(minutes: Int) {
        startForegroundCompat()
        prefs.isBreakDue = false
        prefs.snoozeRemainingSeconds = minutes * 60L
        secondsSinceLastEscalation = 0L
        notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
        showToast("Reminder snoozed for $minutes min")
        broadcastStateUpdate()
        if (prefs.isRunning) {
            restartTicker()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun restartTicker() {
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun startForegroundCompat() {
        val notification = buildOngoingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID_ONGOING, notification)
        }
    }

    private fun triggerBreakReminder(isEscalation: Boolean) {
        prefs.isBreakDue = true
        secondsSinceLastEscalation = 0L

        val intervalText = TimeFormatter.formatCompact(prefs.intervalHours, prefs.intervalMinutes)
        val title = if (isEscalation) {
            "Gentle nudge: still on your phone?"
        } else {
            "Time for a screen break"
        }
        val body = if (isEscalation) {
            "Step away for 3 minutes to protect your Discipline Streak (${prefs.currentStreak})."
        } else {
            "You've reached your $intervalText screen time limit. Rest your eyes for a few minutes."
        }

        if (prefs.toastEnabled) {
            showToast(if (isEscalation) "ScreenTime Guardian: Still time for a quick break!" else "ScreenTime Guardian: $intervalText reached — take a break!")
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val breakActionIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_TAKE_BREAK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze5Intent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_SNOOZE_5),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze15Intent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_SNOOZE_15),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_guardian_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Took a break", breakActionIntent)
            .addAction(0, "Snooze 5m", snooze5Intent)
            .addAction(0, "Snooze 15m", snooze15Intent)
            .build()

        notificationManager.notify(NOTIF_ID_BREAK_ALERT, alertNotification)
        updateOngoingNotification()
        broadcastStateUpdate()
    }

    private fun buildOngoingNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            prefs.snoozeRemainingSeconds > 0L ->
                "Snoozed • next reminder in ${TimeFormatter.formatHms(prefs.snoozeRemainingSeconds)}"
            prefs.isBreakDue ->
                "Break due • tap to log your break or snooze"
            else -> {
                val remaining = (prefs.targetIntervalSeconds - prefs.elapsedSessionSeconds).coerceAtLeast(0L)
                "Next break in ${TimeFormatter.formatHms(remaining)} • Streak: ${prefs.currentStreak}"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setSmallIcon(R.drawable.ic_guardian_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateOngoingNotification() {
        notificationManager.notify(NOTIF_ID_ONGOING, buildOngoingNotification())
    }

    private fun createNotificationChannels() {
        val ongoingChannel = NotificationChannel(
            CHANNEL_ONGOING_ID,
            getString(R.string.channel_ongoing_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_ongoing_desc)
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT_ID,
            getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_alert_desc)
            enableVibration(true)
        }

        notificationManager.createNotificationChannel(ongoingChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun broadcastStateUpdate() {
        val intent = Intent(ACTION_STATE_UPDATED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.screentime.guardian.ACTION_START"
        const val ACTION_PAUSE = "com.screentime.guardian.ACTION_PAUSE"
        const val ACTION_RESET = "com.screentime.guardian.ACTION_RESET"
        const val ACTION_TAKE_BREAK = "com.screentime.guardian.ACTION_TAKE_BREAK"
        const val ACTION_SNOOZE_5 = "com.screentime.guardian.ACTION_SNOOZE_5"
        const val ACTION_SNOOZE_10 = "com.screentime.guardian.ACTION_SNOOZE_10"
        const val ACTION_SNOOZE_15 = "com.screentime.guardian.ACTION_SNOOZE_15"
        const val ACTION_STATE_UPDATED = "com.screentime.guardian.ACTION_STATE_UPDATED"

        private const val CHANNEL_ONGOING_ID = "guardian_ongoing_channel"
        private const val CHANNEL_ALERT_ID = "guardian_alert_channel"
        private const val NOTIF_ID_ONGOING = 1001
        private const val NOTIF_ID_BREAK_ALERT = 1002

        private const val ESCALATION_INTERVAL_SECONDS = 600L // 10 minutes
        private const val MAX_ESCALATIONS_BEFORE_STREAK_RESET = 3
        private const val NATURAL_BREAK_SECONDS = 180L // 3 minutes screen off
    }
}
