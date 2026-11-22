package com.screentime.guardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import com.screentime.guardian.CooldownActivity
import com.screentime.guardian.MainActivity
import com.screentime.guardian.R
import com.screentime.guardian.data.GuardianPrefs
import com.screentime.guardian.receiver.NotificationActionReceiver
import com.screentime.guardian.util.PermissionHelper
import com.screentime.guardian.util.TimeFormatter

class ScreenTimeService : Service() {

    private lateinit var prefs: GuardianPrefs
    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var secondsSinceLastEscalation = 0L
    private var secondsSinceLastDailyAlert = Long.MAX_VALUE / 2
    private var secondsSinceAppPoll = 0L
    private var currentWatchedPackage: String? = null
    private var currentWatchedAppSeconds = 0L
    private var lastWatchedAlertSeconds = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!prefs.isRunning && prefs.focusRemainingSeconds <= 0L) return

            // 1. Focus Mode countdown (ticks regardless of screen on/off)
            if (prefs.focusRemainingSeconds > 0L) {
                prefs.focusRemainingSeconds -= 1L
                if (prefs.focusRemainingSeconds == 0L) {
                    finishFocusMode(completedNaturally = true)
                }
            }

            // 2. Active screen-on tracking
            if (prefs.isRunning && isScreenOn) {
                prefs.todayScreenSeconds += 1L

                // Session break timer
                if (prefs.snoozeRemainingSeconds > 0L) {
                    prefs.snoozeRemainingSeconds -= 1L
                    if (prefs.snoozeRemainingSeconds == 0L) {
                        triggerBreakReminder(isEscalation = false)
                    }
                } else if (!prefs.isBreakDue) {
                    prefs.elapsedSessionSeconds += 1L
                    if (prefs.elapsedSessionSeconds >= prefs.effectiveTargetIntervalSeconds) {
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

                // 3. Daily Overall Screen-Time Threshold repeating high-priority alert
                if (prefs.dailyThresholdEnabled &&
                    !prefs.dailyAlertMutedToday &&
                    prefs.todayScreenSeconds >= prefs.dailyLimitSeconds
                ) {
                    secondsSinceLastDailyAlert += 1L
                    val repeatSec = prefs.dailyRepeatMinutes * 60L
                    if (secondsSinceLastDailyAlert >= repeatSec) {
                        secondsSinceLastDailyAlert = 0L
                        triggerDailyThresholdAlert()
                    }
                }

                // 4. Per-App Doomscroll Guard (polls every 15s for battery efficiency)
                if (prefs.doomscrollEnabled) {
                    secondsSinceAppPoll += 1L
                    if (secondsSinceAppPoll >= APP_POLL_INTERVAL_SECONDS) {
                        secondsSinceAppPoll = 0L
                        checkForegroundDoomscrollApp()
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
                    currentWatchedPackage = null
                    currentWatchedAppSeconds = 0L
                    lastWatchedAlertSeconds = 0L
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    handleScreenWake()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    prefs.todayUnlocks += 1
                    handleScreenWake()
                }
            }
        }
    }

    private fun handleScreenWake() {
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
                notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                notificationManager.cancel(NOTIF_ID_DAILY_LIMIT)
                if (prefs.focusRemainingSeconds <= 0L) {
                    handler.removeCallbacks(tickRunnable)
                    broadcastStateUpdate()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                broadcastStateUpdate()
            }
            ACTION_RESET -> {
                prefs.resetSessionOnly()
                secondsSinceLastEscalation = 0L
                notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
                if (prefs.isRunning || prefs.focusRemainingSeconds > 0L) {
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
                if (prefs.isRunning || prefs.focusRemainingSeconds > 0L) {
                    restartTicker()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_SNOOZE_5 -> handleSnooze(5)
            ACTION_SNOOZE_15 -> handleSnooze(15)
            ACTION_MUTE_DAILY_TODAY -> {
                startForegroundCompat()
                prefs.dailyAlertMutedToday = true
                notificationManager.cancel(NOTIF_ID_DAILY_LIMIT)
                showToast("Daily limit alerts paused until midnight")
                broadcastStateUpdate()
                if (prefs.isRunning || prefs.focusRemainingSeconds > 0L) {
                    restartTicker()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START_FOCUS -> {
                startForegroundCompat()
                startFocusMode()
                restartTicker()
            }
            ACTION_STOP_FOCUS -> {
                startForegroundCompat()
                finishFocusMode(completedNaturally = false)
                if (prefs.isRunning) {
                    restartTicker()
                } else {
                    handler.removeCallbacks(tickRunnable)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                if (prefs.isRunning || prefs.focusRemainingSeconds > 0L) {
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

    private fun startFocusMode() {
        val mins = prefs.focusDurationMinutes
        prefs.focusRemainingSeconds = mins * 60L
        if (PermissionHelper.hasDndAccess(this)) {
            prefs.previousDndFilter = notificationManager.currentInterruptionFilter
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            showToast("Focus Mode started for ${mins}m (DND enabled)")
        } else {
            showToast("Focus Mode started for ${mins}m")
        }
        broadcastStateUpdate()
    }

    private fun finishFocusMode(completedNaturally: Boolean) {
        prefs.focusRemainingSeconds = 0L
        if (PermissionHelper.hasDndAccess(this) && prefs.previousDndFilter != -1) {
            notificationManager.setInterruptionFilter(prefs.previousDndFilter)
            prefs.previousDndFilter = -1
        }
        if (completedNaturally) {
            showToast("Focus sprint complete! Great job staying locked in.")
            val openAppIntent = PendingIntent.getActivity(
                this,
                300,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
                .setSmallIcon(R.drawable.ic_guardian_notification)
                .setContentTitle("Focus sprint complete")
                .setContentText("Notifications restored. Take a short breather before your next block.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent)
                .build()
            notificationManager.notify(NOTIF_ID_FOCUS_DONE, notif)
        } else {
            showToast("Focus Mode ended")
        }
        broadcastStateUpdate()
    }

    private fun checkForegroundDoomscrollApp() {
        if (!PermissionHelper.hasUsageStatsPermission(this)) return
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 60_000L, now)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
            }
        }
        val activePkg = latestPkg ?: currentWatchedPackage ?: return
        val appLabel = GuardianPrefs.WATCHED_APP_LABELS[activePkg]
        if (appLabel != null) {
            if (activePkg == currentWatchedPackage) {
                currentWatchedAppSeconds += APP_POLL_INTERVAL_SECONDS
            } else {
                currentWatchedPackage = activePkg
                currentWatchedAppSeconds = APP_POLL_INTERVAL_SECONDS
                lastWatchedAlertSeconds = 0L
            }
            val limitSec = prefs.doomscrollLimitMinutes * 60L
            if (currentWatchedAppSeconds >= limitSec &&
                (currentWatchedAppSeconds - lastWatchedAlertSeconds) >= limitSec
            ) {
                lastWatchedAlertSeconds = currentWatchedAppSeconds
                triggerDoomscrollNudge(appLabel, (currentWatchedAppSeconds / 60L).toInt())
            }
        } else {
            currentWatchedPackage = null
            currentWatchedAppSeconds = 0L
            lastWatchedAlertSeconds = 0L
        }
    }

    private fun triggerDoomscrollNudge(appLabel: String, minutesSpent: Int) {
        if (prefs.toastEnabled) {
            showToast("Doomscroll Guard: ${minutesSpent}m in $appLabel — time to pause?")
        }
        val cooldownIntent = PendingIntent.getActivity(
            this,
            250,
            Intent(this, CooldownActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_guardian_notification)
            .setContentTitle("Still scrolling $appLabel?")
            .setContentText("You've spent ${minutesSpent}m straight in $appLabel. Take a 60s cooldown.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(cooldownIntent)
            .addAction(0, "60s Cooldown", cooldownIntent)
            .build()
        notificationManager.notify(NOTIF_ID_DOOMSCROLL, notif)
    }

    private fun triggerDailyThresholdAlert() {
        val totalText = TimeFormatter.formatDurationSummary(prefs.todayScreenSeconds)
        val capText = TimeFormatter.formatCompact(prefs.dailyLimitHours, prefs.dailyLimitMinutes)
        val repeatMins = prefs.dailyRepeatMinutes

        if (prefs.toastEnabled) {
            showToast("Daily limit exceeded ($totalText / $capText) — repeating every ${repeatMins}m")
        }

        val cooldownIntent = PendingIntent.getActivity(
            this,
            200,
            Intent(this, CooldownActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteTodayIntent = PendingIntent.getBroadcast(
            this,
            201,
            Intent(this, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_MUTE_DAILY_TODAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_DAILY_LIMIT_ID)
            .setSmallIcon(R.drawable.ic_guardian_notification)
            .setContentTitle("Daily screen time limit reached ($totalText)")
            .setContentText("You crossed your $capText daily cap. This alert repeats every ${repeatMins}m while your screen is on.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "You crossed your $capText daily cap (currently $totalText today). Step away for a 60s cooldown, or pause alerts until midnight."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(cooldownIntent)
            .addAction(0, "60s Cooldown", cooldownIntent)
            .addAction(0, "Pause today", muteTodayIntent)
            .build()

        notificationManager.notify(NOTIF_ID_DAILY_LIMIT, alertNotification)
    }

    private fun handleSnooze(minutes: Int) {
        startForegroundCompat()
        prefs.isBreakDue = false
        prefs.snoozeRemainingSeconds = minutes * 60L
        secondsSinceLastEscalation = 0L
        notificationManager.cancel(NOTIF_ID_BREAK_ALERT)
        showToast("Reminder snoozed for $minutes min")
        broadcastStateUpdate()
        if (prefs.isRunning || prefs.focusRemainingSeconds > 0L) {
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

        val intervalText = TimeFormatter.formatDurationSummary(prefs.effectiveTargetIntervalSeconds)
        val bedtimeSuffix = if (prefs.isCurrentlyBedtimeWindow) " (Bedtime rule)" else ""
        val title = if (isEscalation) {
            "Gentle nudge: still on your phone?"
        } else {
            "Time for a screen break$bedtimeSuffix"
        }
        val body = if (isEscalation) {
            "Step away for 60s to protect your Discipline Streak (${prefs.currentStreak})."
        } else {
            "You've reached your $intervalText session limit. Rest your eyes for a moment."
        }

        if (prefs.toastEnabled) {
            showToast(if (isEscalation) "ScreenTime Guardian: Still time for a quick break!" else "ScreenTime Guardian: $intervalText reached — take a break!")
        }

        val cooldownIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, CooldownActivity::class.java),
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
            .setContentIntent(cooldownIntent)
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
            prefs.focusRemainingSeconds > 0L ->
                "Focus Mode • ${TimeFormatter.formatHms(prefs.focusRemainingSeconds)} left"
            prefs.snoozeRemainingSeconds > 0L ->
                "Snoozed • next reminder in ${TimeFormatter.formatHms(prefs.snoozeRemainingSeconds)}"
            prefs.isBreakDue ->
                "Break due • tap to start 60s cooldown or log break"
            else -> {
                val remaining = (prefs.effectiveTargetIntervalSeconds - prefs.elapsedSessionSeconds).coerceAtLeast(0L)
                val todayStr = TimeFormatter.formatDurationSummary(prefs.todayScreenSeconds)
                "Break in ${TimeFormatter.formatHms(remaining)} • Today: $todayStr"
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

        val dailyLimitChannel = NotificationChannel(
            CHANNEL_DAILY_LIMIT_ID,
            getString(R.string.channel_daily_limit_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_daily_limit_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 150, 300)
        }

        notificationManager.createNotificationChannel(ongoingChannel)
        notificationManager.createNotificationChannel(alertChannel)
        notificationManager.createNotificationChannel(dailyLimitChannel)
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
        const val ACTION_SNOOZE_15 = "com.screentime.guardian.ACTION_SNOOZE_15"
        const val ACTION_MUTE_DAILY_TODAY = "com.screentime.guardian.ACTION_MUTE_DAILY_TODAY"
        const val ACTION_START_FOCUS = "com.screentime.guardian.ACTION_START_FOCUS"
        const val ACTION_STOP_FOCUS = "com.screentime.guardian.ACTION_STOP_FOCUS"
        const val ACTION_STATE_UPDATED = "com.screentime.guardian.ACTION_STATE_UPDATED"

        private const val CHANNEL_ONGOING_ID = "guardian_ongoing_channel"
        private const val CHANNEL_ALERT_ID = "guardian_alert_channel"
        private const val CHANNEL_DAILY_LIMIT_ID = "guardian_daily_limit_channel"

        private const val NOTIF_ID_ONGOING = 1001
        private const val NOTIF_ID_BREAK_ALERT = 1002
        private const val NOTIF_ID_DAILY_LIMIT = 1003
        private const val NOTIF_ID_DOOMSCROLL = 1004
        private const val NOTIF_ID_FOCUS_DONE = 1005

        private const val ESCALATION_INTERVAL_SECONDS = 600L // 10 minutes
        private const val MAX_ESCALATIONS_BEFORE_STREAK_RESET = 3
        private const val NATURAL_BREAK_SECONDS = 180L // 3 minutes screen off
        private const val APP_POLL_INTERVAL_SECONDS = 15L
    }
}
