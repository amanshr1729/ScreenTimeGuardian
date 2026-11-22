package com.screentime.guardian

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screentime.guardian.data.GuardianPrefs
import com.screentime.guardian.databinding.ActivityMainBinding
import com.screentime.guardian.service.ScreenTimeService
import com.screentime.guardian.util.PermissionHelper
import com.screentime.guardian.util.TimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: GuardianPrefs

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                sendServiceAction(ScreenTimeService.ACTION_START)
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required so reminders can reach you.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val stateUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPrefs(this)
        setupControls()
        syncInputsFromPrefs()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ScreenTimeService.ACTION_STATE_UPDATED)
        ContextCompat.registerReceiver(
            this,
            stateUpdateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateBatteryBannerVisibility()
        updateUsageButtonVisibility()
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateUpdateReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun setupControls() {
        binding.btnStartPause.setOnClickListener {
            if (prefs.isRunning) {
                sendServiceAction(ScreenTimeService.ACTION_PAUSE)
            } else {
                startMonitoringWithPermissionCheck()
            }
        }

        binding.btnReset.setOnClickListener {
            if (prefs.isRunning) {
                sendServiceAction(ScreenTimeService.ACTION_RESET)
            } else {
                prefs.resetSessionOnly()
                refreshUi()
            }
            Toast.makeText(this, "Session timer reset", Toast.LENGTH_SHORT).show()
        }

        binding.btnTookBreak.setOnClickListener {
            if (prefs.isRunning) {
                sendServiceAction(ScreenTimeService.ACTION_TAKE_BREAK)
            } else {
                prefs.recordBreakTaken()
                refreshUi()
                Toast.makeText(
                    this,
                    "Break logged! Discipline Streak: ${prefs.currentStreak}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnStartCooldown.setOnClickListener {
            startActivity(Intent(this, CooldownActivity::class.java))
        }

        // Session interval presets & apply
        binding.chip30m.setOnClickListener { applyQuickPreset(0, 30) }
        binding.chip1h.setOnClickListener { applyQuickPreset(1, 0) }
        binding.chip1h30m.setOnClickListener { applyQuickPreset(1, 30) }
        binding.chip2h30m.setOnClickListener { applyQuickPreset(2, 30) }

        binding.btnApplyInterval.setOnClickListener {
            val hours = binding.etHours.text?.toString()?.toIntOrNull() ?: 0
            val minutes = binding.etMinutes.text?.toString()?.toIntOrNull() ?: 0
            if (hours == 0 && minutes == 0) {
                Toast.makeText(this, "Please set at least 1 minute.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.intervalHours = hours.coerceIn(0, 12)
            prefs.intervalMinutes = minutes.coerceIn(0, 59)
            syncInputsFromPrefs()
            if (prefs.isRunning) {
                sendServiceAction(ScreenTimeService.ACTION_RESET)
            } else {
                prefs.resetSessionOnly()
                refreshUi()
            }
            val label = TimeFormatter.formatCompact(prefs.intervalHours, prefs.intervalMinutes)
            Toast.makeText(this, "Session interval set to $label", Toast.LENGTH_SHORT).show()
        }

        binding.switchToast.setOnCheckedChangeListener { _, isChecked ->
            prefs.toastEnabled = isChecked
        }

        binding.switchEscalation.setOnCheckedChangeListener { _, isChecked ->
            prefs.escalationEnabled = isChecked
        }

        // Daily overall threshold repeating alert
        binding.switchDailyThreshold.setOnCheckedChangeListener { _, isChecked ->
            prefs.dailyThresholdEnabled = isChecked
            if (isChecked) prefs.dailyAlertMutedToday = false
        }

        binding.btnSaveDailyThreshold.setOnClickListener {
            val hours = binding.etDailyHours.text?.toString()?.toIntOrNull() ?: 0
            val minutes = binding.etDailyMinutes.text?.toString()?.toIntOrNull() ?: 0
            val repeat = binding.etDailyRepeatMins.text?.toString()?.toIntOrNull() ?: 10
            if (hours == 0 && minutes == 0) {
                Toast.makeText(this, "Daily cap must be at least 1 minute.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.dailyLimitHours = hours.coerceIn(0, 23)
            prefs.dailyLimitMinutes = minutes.coerceIn(0, 59)
            prefs.dailyRepeatMinutes = repeat.coerceIn(1, 60)
            prefs.dailyAlertMutedToday = false
            syncInputsFromPrefs()
            refreshUi()
            val cap = TimeFormatter.formatCompact(prefs.dailyLimitHours, prefs.dailyLimitMinutes)
            Toast.makeText(
                this,
                "Daily cap set to $cap (alerts every ${prefs.dailyRepeatMinutes}m)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Focus Mode (Auto DND)
        binding.chipFocus25.setOnClickListener {
            prefs.focusDurationMinutes = 25
            refreshUi()
        }
        binding.chipFocus50.setOnClickListener {
            prefs.focusDurationMinutes = 50
            refreshUi()
        }
        binding.chipFocus90.setOnClickListener {
            prefs.focusDurationMinutes = 90
            refreshUi()
        }

        binding.btnToggleFocus.setOnClickListener {
            if (prefs.focusRemainingSeconds > 0L) {
                sendServiceAction(ScreenTimeService.ACTION_STOP_FOCUS)
            } else {
                if (!PermissionHelper.hasDndAccess(this)) {
                    Toast.makeText(
                        this,
                        "Allow Do Not Disturb access to silence notifications during Focus Mode.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return@setOnClickListener
                }
                sendServiceAction(ScreenTimeService.ACTION_START_FOCUS)
            }
        }

        // Bedtime wind-down rule
        binding.switchBedtime.setOnCheckedChangeListener { _, isChecked ->
            prefs.bedtimeEnabled = isChecked
            refreshUi()
        }

        binding.btnSaveBedtime.setOnClickListener {
            val start = binding.etBedtimeStart.text?.toString()?.toIntOrNull() ?: 22
            val end = binding.etBedtimeEnd.text?.toString()?.toIntOrNull() ?: 7
            val mins = binding.etBedtimeInterval.text?.toString()?.toIntOrNull() ?: 15
            prefs.bedtimeStartHour = start.coerceIn(0, 23)
            prefs.bedtimeEndHour = end.coerceIn(0, 23)
            prefs.bedtimeIntervalMinutes = mins.coerceIn(5, 60)
            syncInputsFromPrefs()
            refreshUi()
            Toast.makeText(
                this,
                "Bedtime rule saved (${prefs.bedtimeStartHour}:00–${prefs.bedtimeEndHour}:00, every ${prefs.bedtimeIntervalMinutes}m)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Per-App Doomscroll Guard
        binding.switchDoomscroll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !PermissionHelper.hasUsageStatsPermission(this)) {
                binding.switchDoomscroll.isChecked = false
                Toast.makeText(
                    this,
                    "Please grant Usage Access first so Guardian can detect time-sink apps.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return@setOnCheckedChangeListener
            }
            prefs.doomscrollEnabled = isChecked
        }

        binding.btnSaveDoomscroll.setOnClickListener {
            val mins = binding.etAppLimitMins.text?.toString()?.toIntOrNull() ?: 15
            prefs.doomscrollLimitMinutes = mins.coerceIn(1, 120)
            syncInputsFromPrefs()
            Toast.makeText(
                this,
                "App guard set to ${prefs.doomscrollLimitMinutes}m per watched app",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnGrantUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.cardBatteryBanner.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun applyQuickPreset(hours: Int, minutes: Int) {
        prefs.intervalHours = hours
        prefs.intervalMinutes = minutes
        syncInputsFromPrefs()
        if (prefs.isRunning) {
            sendServiceAction(ScreenTimeService.ACTION_RESET)
        } else {
            prefs.resetSessionOnly()
            refreshUi()
        }
    }

    private fun syncInputsFromPrefs() {
        binding.etHours.setText(prefs.intervalHours.toString())
        binding.etMinutes.setText(prefs.intervalMinutes.toString())
        binding.switchToast.isChecked = prefs.toastEnabled
        binding.switchEscalation.isChecked = prefs.escalationEnabled

        binding.switchDailyThreshold.isChecked = prefs.dailyThresholdEnabled
        binding.etDailyHours.setText(prefs.dailyLimitHours.toString())
        binding.etDailyMinutes.setText(prefs.dailyLimitMinutes.toString())
        binding.etDailyRepeatMins.setText(prefs.dailyRepeatMinutes.toString())

        binding.switchBedtime.isChecked = prefs.bedtimeEnabled
        binding.etBedtimeStart.setText(prefs.bedtimeStartHour.toString())
        binding.etBedtimeEnd.setText(prefs.bedtimeEndHour.toString())
        binding.etBedtimeInterval.setText(prefs.bedtimeIntervalMinutes.toString())

        binding.switchDoomscroll.isChecked =
            prefs.doomscrollEnabled && PermissionHelper.hasUsageStatsPermission(this)
        binding.etAppLimitMins.setText(prefs.doomscrollLimitMinutes.toString())
    }

    private fun startMonitoringWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        sendServiceAction(ScreenTimeService.ACTION_START)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, ScreenTimeService::class.java).apply {
            this.action = action
        }
        if (action == ScreenTimeService.ACTION_START || action == ScreenTimeService.ACTION_START_FOCUS) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        refreshUi()
    }

    private fun refreshUi() {
        val targetSec = prefs.effectiveTargetIntervalSeconds
        val elapsedSec = prefs.elapsedSessionSeconds
        val snoozeSec = prefs.snoozeRemainingSeconds

        val displayRemaining = when {
            snoozeSec > 0L -> snoozeSec
            prefs.isBreakDue -> 0L
            else -> (targetSec - elapsedSec).coerceAtLeast(0L)
        }

        val percentElapsed = if (targetSec > 0L) {
            ((elapsedSec * 100L) / targetSec).toInt().coerceIn(0, 100)
        } else {
            0
        }

        binding.tvCountdown.text = TimeFormatter.formatHms(displayRemaining)
        binding.tvElapsedPercent.text = "$percentElapsed% elapsed"
        binding.progressSession.progress = percentElapsed

        val activeIntervalLabel = if (prefs.isCurrentlyBedtimeWindow) {
            "${prefs.bedtimeIntervalMinutes}m (Night)"
        } else {
            TimeFormatter.formatCompact(prefs.intervalHours, prefs.intervalMinutes)
        }
        binding.tvIntervalSummary.text = activeIntervalLabel
        binding.tvTodaysBreaks.text = prefs.todayBreaks.toString()
        binding.tvTodaysUnlocks.text = prefs.todayUnlocks.toString()

        binding.tvStreakValue.text = "${prefs.currentStreak} in a row"
        val screenTimeText = TimeFormatter.formatDurationSummary(prefs.todayScreenSeconds)
        val capText = TimeFormatter.formatCompact(prefs.dailyLimitHours, prefs.dailyLimitMinutes)
        binding.tvDailySummary.text =
            "Today: $screenTimeText / $capText cap  •  Best streak: ${prefs.bestStreak}"

        val capProgress = if (prefs.dailyLimitSeconds > 0L) {
            ((prefs.todayScreenSeconds * 100L) / prefs.dailyLimitSeconds).toInt().coerceIn(0, 100)
        } else {
            0
        }
        binding.progressDailyCap.progress = capProgress

        // Focus mode UI
        if (prefs.focusRemainingSeconds > 0L) {
            binding.tvFocusCountdown.text = TimeFormatter.formatHms(prefs.focusRemainingSeconds)
            binding.btnToggleFocus.text = getString(R.string.btn_stop_focus)
        } else {
            binding.tvFocusCountdown.text = "${prefs.focusDurationMinutes}m ready"
            binding.btnToggleFocus.text = "${getString(R.string.btn_start_focus)} (${prefs.focusDurationMinutes}m)"
        }

        binding.btnStartPause.text = if (prefs.isRunning) {
            getString(R.string.btn_pause_timer)
        } else {
            getString(R.string.btn_start_timer)
        }

        val overDailyCap = prefs.dailyThresholdEnabled && prefs.todayScreenSeconds >= prefs.dailyLimitSeconds
        binding.tvStatusBadge.text = when {
            prefs.focusRemainingSeconds > 0L -> getString(R.string.status_focus)
            !prefs.isRunning -> getString(R.string.status_paused)
            overDailyCap -> getString(R.string.status_limit_over)
            prefs.snoozeRemainingSeconds > 0L -> getString(R.string.status_snoozed)
            prefs.isBreakDue -> getString(R.string.status_break_due)
            else -> getString(R.string.status_monitoring)
        }

        // 7-day history list
        val history = prefs.getHistory()
        if (history.isEmpty()) {
            binding.tvHistoryList.text = getString(R.string.empty_history)
        } else {
            binding.tvHistoryList.text = history.joinToString("\n") { day ->
                val dur = TimeFormatter.formatDurationSummary(day.screenSeconds)
                "${day.date}  |  $dur  |  ${day.breaks} breaks  |  ${day.unlocks} unlocks"
            }
        }
    }

    private fun updateBatteryBannerVisibility() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        binding.cardBatteryBanner.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    private fun updateUsageButtonVisibility() {
        val granted = PermissionHelper.hasUsageStatsPermission(this)
        binding.btnGrantUsageAccess.visibility = if (granted) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
