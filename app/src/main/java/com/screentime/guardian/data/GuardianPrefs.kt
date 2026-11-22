package com.screentime.guardian.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

data class DailyHistoryEntry(
    val date: String,
    val screenSeconds: Long,
    val breaks: Int,
    val unlocks: Int,
    val streak: Int
)

class GuardianPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun ensureTodayFresh() {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_STATS_DATE, null)
        if (storedDate != null && storedDate != today) {
            archiveDay(
                DailyHistoryEntry(
                    date = storedDate,
                    screenSeconds = prefs.getLong(KEY_TODAY_SCREEN_SECONDS, 0L),
                    breaks = prefs.getInt(KEY_TODAY_BREAKS, 0),
                    unlocks = prefs.getInt(KEY_TODAY_UNLOCKS, 0),
                    streak = prefs.getInt(KEY_CURRENT_STREAK, 0)
                )
            )
            prefs.edit()
                .putString(KEY_STATS_DATE, today)
                .putInt(KEY_TODAY_BREAKS, 0)
                .putInt(KEY_TODAY_UNLOCKS, 0)
                .putLong(KEY_TODAY_SCREEN_SECONDS, 0L)
                .putBoolean(KEY_DAILY_ALERT_MUTED_TODAY, false)
                .apply()
        } else if (storedDate == null) {
            prefs.edit().putString(KEY_STATS_DATE, today).apply()
        }
    }

    private fun archiveDay(entry: DailyHistoryEntry) {
        val list = getHistory().toMutableList()
        list.removeAll { it.date == entry.date }
        list.add(0, entry)
        val trimmed = list.take(7)
        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("date", item.date)
                    put("screenSeconds", item.screenSeconds)
                    put("breaks", item.breaks)
                    put("unlocks", item.unlocks)
                    put("streak", item.streak)
                }
            )
        }
        prefs.edit().putString(KEY_WEEKLY_HISTORY, arr.toString()).apply()
    }

    fun getHistory(): List<DailyHistoryEntry> {
        val raw = prefs.getString(KEY_WEEKLY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        DailyHistoryEntry(
                            date = obj.optString("date", ""),
                            screenSeconds = obj.optLong("screenSeconds", 0L),
                            breaks = obj.optInt("breaks", 0),
                            unlocks = obj.optInt("unlocks", 0),
                            streak = obj.optInt("streak", 0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Session break interval
    var intervalHours: Int
        get() = prefs.getInt(KEY_INTERVAL_HOURS, 2)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_HOURS, value.coerceIn(0, 12)).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL_MINUTES, 30)
        set(value) = prefs.edit().putInt(KEY_INTERVAL_MINUTES, value.coerceIn(0, 59)).apply()

    val isCurrentlyBedtimeWindow: Boolean
        get() {
            if (!bedtimeEnabled) return false
            val hour = LocalTime.now().hour
            val start = bedtimeStartHour
            val end = bedtimeEndHour
            return if (start > end) {
                hour >= start || hour < end
            } else {
                hour in start until end
            }
        }

    val effectiveTargetIntervalSeconds: Long
        get() {
            if (isCurrentlyBedtimeWindow) {
                return bedtimeIntervalMinutes.coerceAtLeast(1) * 60L
            }
            return ((intervalHours * 60L) + intervalMinutes).coerceAtLeast(1L) * 60L
        }

    // Daily overall screen-time threshold alert
    var dailyThresholdEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_THRESHOLD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_THRESHOLD_ENABLED, value).apply()

    var dailyLimitHours: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT_HOURS, 4)
        set(value) = prefs.edit().putInt(KEY_DAILY_LIMIT_HOURS, value.coerceIn(0, 23)).apply()

    var dailyLimitMinutes: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT_MINUTES, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_LIMIT_MINUTES, value.coerceIn(0, 59)).apply()

    val dailyLimitSeconds: Long
        get() = ((dailyLimitHours * 60L) + dailyLimitMinutes).coerceAtLeast(1L) * 60L

    var dailyRepeatMinutes: Int
        get() = prefs.getInt(KEY_DAILY_REPEAT_MINUTES, 10)
        set(value) = prefs.edit().putInt(KEY_DAILY_REPEAT_MINUTES, value.coerceIn(1, 60)).apply()

    var dailyAlertMutedToday: Boolean
        get() {
            ensureTodayFresh()
            return prefs.getBoolean(KEY_DAILY_ALERT_MUTED_TODAY, false)
        }
        set(value) {
            ensureTodayFresh()
            prefs.edit().putBoolean(KEY_DAILY_ALERT_MUTED_TODAY, value).apply()
        }

    // Bedtime rule
    var bedtimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEDTIME_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BEDTIME_ENABLED, value).apply()

    var bedtimeStartHour: Int
        get() = prefs.getInt(KEY_BEDTIME_START_HOUR, 22)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_START_HOUR, value.coerceIn(0, 23)).apply()

    var bedtimeEndHour: Int
        get() = prefs.getInt(KEY_BEDTIME_END_HOUR, 7)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_END_HOUR, value.coerceIn(0, 23)).apply()

    var bedtimeIntervalMinutes: Int
        get() = prefs.getInt(KEY_BEDTIME_INTERVAL_MINUTES, 15)
        set(value) = prefs.edit().putInt(KEY_BEDTIME_INTERVAL_MINUTES, value.coerceIn(5, 60)).apply()

    // Focus mode (DND)
    var focusDurationMinutes: Int
        get() = prefs.getInt(KEY_FOCUS_DURATION_MINUTES, 25)
        set(value) = prefs.edit().putInt(KEY_FOCUS_DURATION_MINUTES, value.coerceIn(5, 180)).apply()

    var focusRemainingSeconds: Long
        get() = prefs.getLong(KEY_FOCUS_REMAINING_SECONDS, 0L)
        set(value) = prefs.edit().putLong(KEY_FOCUS_REMAINING_SECONDS, value.coerceAtLeast(0L)).apply()

    var previousDndFilter: Int
        get() = prefs.getInt(KEY_PREVIOUS_DND_FILTER, -1)
        set(value) = prefs.edit().putInt(KEY_PREVIOUS_DND_FILTER, value).apply()

    // Per-App Doomscroll Guard
    var doomscrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOOMSCROLL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOOMSCROLL_ENABLED, value).apply()

    var doomscrollLimitMinutes: Int
        get() = prefs.getInt(KEY_DOOMSCROLL_LIMIT_MINUTES, 15)
        set(value) = prefs.edit().putInt(KEY_DOOMSCROLL_LIMIT_MINUTES, value.coerceIn(1, 120)).apply()

    // Core session state
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

    // Daily stats
    var todayBreaks: Int
        get() {
            ensureTodayFresh()
            return prefs.getInt(KEY_TODAY_BREAKS, 0)
        }
        set(value) {
            ensureTodayFresh()
            prefs.edit().putInt(KEY_TODAY_BREAKS, value.coerceAtLeast(0)).apply()
        }

    var todayUnlocks: Int
        get() {
            ensureTodayFresh()
            return prefs.getInt(KEY_TODAY_UNLOCKS, 0)
        }
        set(value) {
            ensureTodayFresh()
            prefs.edit().putInt(KEY_TODAY_UNLOCKS, value.coerceAtLeast(0)).apply()
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
        private const val KEY_WEEKLY_HISTORY = "weekly_history"
        private const val KEY_INTERVAL_HOURS = "interval_hours"
        private const val KEY_INTERVAL_MINUTES = "interval_minutes"
        private const val KEY_DAILY_THRESHOLD_ENABLED = "daily_threshold_enabled"
        private const val KEY_DAILY_LIMIT_HOURS = "daily_limit_hours"
        private const val KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes"
        private const val KEY_DAILY_REPEAT_MINUTES = "daily_repeat_minutes"
        private const val KEY_DAILY_ALERT_MUTED_TODAY = "daily_alert_muted_today"
        private const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
        private const val KEY_BEDTIME_START_HOUR = "bedtime_start_hour"
        private const val KEY_BEDTIME_END_HOUR = "bedtime_end_hour"
        private const val KEY_BEDTIME_INTERVAL_MINUTES = "bedtime_interval_minutes"
        private const val KEY_FOCUS_DURATION_MINUTES = "focus_duration_minutes"
        private const val KEY_FOCUS_REMAINING_SECONDS = "focus_remaining_seconds"
        private const val KEY_PREVIOUS_DND_FILTER = "previous_dnd_filter"
        private const val KEY_DOOMSCROLL_ENABLED = "doomscroll_enabled"
        private const val KEY_DOOMSCROLL_LIMIT_MINUTES = "doomscroll_limit_minutes"
        private const val KEY_IS_RUNNING = "is_running"
        private const val KEY_ELAPSED_SESSION_SECONDS = "elapsed_session_seconds"
        private const val KEY_SNOOZE_REMAINING_SECONDS = "snooze_remaining_seconds"
        private const val KEY_IS_BREAK_DUE = "is_break_due"
        private const val KEY_ESCALATION_COUNT = "escalation_count"
        private const val KEY_LAST_SCREEN_OFF_MS = "last_screen_off_ms"
        private const val KEY_TODAY_BREAKS = "today_breaks"
        private const val KEY_TODAY_UNLOCKS = "today_unlocks"
        private const val KEY_TODAY_SCREEN_SECONDS = "today_screen_seconds"
        private const val KEY_CURRENT_STREAK = "current_streak"
        private const val KEY_BEST_STREAK = "best_streak"
        private const val KEY_TOAST_ENABLED = "toast_enabled"
        private const val KEY_ESCALATION_ENABLED = "escalation_enabled"

        val WATCHED_APP_LABELS: Map<String, String> = mapOf(
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.twitter.android" to "X",
            "com.reddit.frontpage" to "Reddit",
            "com.facebook.katana" to "Facebook",
            "com.snapchat.android" to "Snapchat"
        )
    }
}
