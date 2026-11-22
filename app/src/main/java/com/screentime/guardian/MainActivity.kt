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
            Toast.makeText(this, "Reminder interval set to $label", Toast.LENGTH_SHORT).show()
        }

        binding.switchToast.setOnCheckedChangeListener { _, isChecked ->
            prefs.toastEnabled = isChecked
        }

        binding.switchEscalation.setOnCheckedChangeListener { _, isChecked ->
            prefs.escalationEnabled = isChecked
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
        if (action == ScreenTimeService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        refreshUi()
    }

    private fun refreshUi() {
        val targetSec = prefs.targetIntervalSeconds
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

        binding.tvIntervalSummary.text =
            TimeFormatter.formatCompact(prefs.intervalHours, prefs.intervalMinutes)
        binding.tvTodaysBreaks.text = prefs.todayBreaks.toString()

        binding.tvStreakValue.text = "${prefs.currentStreak} in a row"
        val screenTimeText = TimeFormatter.formatDurationSummary(prefs.todayScreenSeconds)
        binding.tvDailySummary.text =
            "Today's screen time: $screenTimeText  •  Best streak: ${prefs.bestStreak}"

        binding.btnStartPause.text = if (prefs.isRunning) {
            getString(R.string.btn_pause_timer)
        } else {
            getString(R.string.btn_start_timer)
        }

        binding.tvStatusBadge.text = when {
            !prefs.isRunning -> getString(R.string.status_paused)
            prefs.snoozeRemainingSeconds > 0L -> getString(R.string.status_snoozed)
            prefs.isBreakDue -> getString(R.string.status_break_due)
            else -> getString(R.string.status_monitoring)
        }
    }

    private fun updateBatteryBannerVisibility() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        binding.cardBatteryBanner.visibility = if (ignoring) View.GONE else View.VISIBLE
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
