package com.screentime.guardian.util

import java.util.Locale

object TimeFormatter {

    fun formatHms(totalSeconds: Long): String {
        val safe = totalSeconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val seconds = safe % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatCompact(hours: Int, minutes: Int): String {
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    fun formatDurationSummary(totalSeconds: Long): String {
        val totalMinutes = (totalSeconds.coerceAtLeast(0L) / 60L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return formatCompact(hours, minutes)
    }
}
