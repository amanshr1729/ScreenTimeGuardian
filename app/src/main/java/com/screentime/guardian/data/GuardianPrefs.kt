package com.screentime.guardian.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

class GuardianPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureTodayFresh() {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_STATS_DATE, null)
        if (storedDate != today) {
            prefs.edit()
                .putString(KEY_STATS_DATE, today)
                .putInt(KEY_TODAY_BREAKS, 0)
                .putLong(KEY_TODAY_SCREEN_SECONDS, 0L)
                .apply()
        }
    }

    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 2)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_HOURS, value.coerceIn(0, 12)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL_MINUTES, 30)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_MINUTES, value.coerceIn(0, 59)).apply()

    val targetIntervalSeconds: Long
        get() = ((intervalHours * 60L) + intervalMinutes).coerceAtLeast(1L) * 60L

    var isRunning: Boolean
        get() = prefs.getBoolean(KEY_IS_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_RUNNING, value).apply()

    var elapsedSessionSeconds: Long
        get() = prefs.getLong(KEY_ELAPSED_SESSION_SECONDS, 0L)
        set(value) = prefs.edit().putLong(KEY_ELAPSED_SESSION_SECONDS, value.coerceAtLeast(0L)).apply()

    var snoozeRemainingSeconds: Long
        get() = prefs.getLong(KEY_SNOOZE_REMAINING_SECONDS, 0L)
        set(value) = prefs.edit().putLong(KEY_SNOOZE_REMAINING_SECONDS, value.coerceAtLeast(0L)).apply()

    var isBreakDue: Boolean
        get() = prefs.getBoolean(KEY_IS_BREAK_DUE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_BREAK_DUE, value).apply()

    var escalationCount: Int
        get() = prefs.getInt(KEY_ESCALATION_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_ESCALATION_COUNT, value.coerceAtLeast(0)).apply()

    var lastScreenOffTimestampMs: Long
        get() = prefs.getLong(KEY_LAST_SCREEN_OFF_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SCREEN_OFF_MS, value).apply()

    var todayBreaks: Int
        get() {
            ensureTodayFresh()
            return prefs.getInt(KEY_TODAY_BREAKS, 0)
        }
        set(value) {
            ensureTodayFresh()
            prefs.edit().putInt(KEY_TODAY_BREAKS, value.coerceAtLeast(0)).apply()
        }

    var todayScreenSeconds: Long
        get() {
            ensureTodayFresh()
            return prefs.getLong(KEY_TODAY_SCREEN_SECONDS, 0L)
        }
        set(value) {
            ensureTodayFresh()
            prefs.edit().putLong(KEY_TODAY_SCREEN_SECONDS, value.coerceAtLeast(0L)).apply()
        }

    var currentStreak: Int
        get() = prefs.getInt(KEY_CURRENT_STREAK, 0)
        set(value) {
            val safe = value.coerceAtLeast(0)
            val best = bestStreak
            prefs.edit()
                .putInt(KEY_CURRENT_STREAK, safe)
                .putInt(KEY_BEST_STREAK, maxOf(best, safe))
                .apply()
        }

    val bestStreak: Int
        get() = prefs.getInt(KEY_BEST_STREAK, 0)

    var toastEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOAST_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TOAST_ENABLED, value).apply()

    var escalationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ESCALATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ESCALATION_ENABLED, value).apply()

    fun recordBreakTaken() {
        ensureTodayFresh()
        todayBreaks += 1
        currentStreak += 1
        elapsedSessionSeconds = 0L
        snoozeRemainingSeconds = 0L
        isBreakDue = false
        escalationCount = 0
    }

    fun resetSessionOnly() {
        elapsedSessionSeconds = 0L
        snoozeRemainingSeconds = 0L
        isBreakDue = false
        escalationCount = 0
    }

    companion object {
        private const val PREFS_NAME = "screentime_guardian_prefs"
        private const val KEY_STATS_DATE = "stats_date"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_ELAPSED_SESSION_SECONDS = "elapsed_session_seconds"
        private const val KEY_SNOOZE_REMAINING_SECONDS = "snooze_remaining_seconds"
        private const val KEY_IS_BREAK_DUE = "is_break_due"
        private const val KEY_ESCALATION_COUNT = "escalation_count"
        private const val KEY_LAST_SCREEN_OFF_MS = "last_screen_off_ms"
        private const val KEY_TODAY_BREAKS = "today_breaks"
        private const val KEY_TODAY_SCREEN_SECONDS = "today_screen_seconds"
        private const val KEY_CURRENT_STREAK = "current_streak"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_TOAST_ENABLED = "toast_enabled"
        private const val KEY_ESCALATION_ENABLED = "escalation_enabled"
    }
}
