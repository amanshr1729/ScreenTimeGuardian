package com.screentime.guardian

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screentime.guardian.data.GuardianPrefs
import com.screentime.guardian.databinding.ActivityCooldownBinding
import com.screentime.guardian.service.ScreenTimeService

class CooldownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCooldownBinding
    private lateinit var prefs: GuardianPrefs
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCooldownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = GuardianPrefs(this)

        binding.btnExitCooldown.setOnClickListener {
            finish()
        }

        startBreathingCountdown()
    }

    private fun startBreathingCountdown() {
        val totalSeconds = 60
        timer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000L).toInt().coerceAtLeast(1)
                val elapsed = totalSeconds - remaining
                binding.tvCooldownSeconds.text = "${remaining}s"
                binding.progressCooldown.progress = elapsed

                val cyclePos = elapsed % 12
                binding.tvBreathPhase.text = when {
                    cyclePos < 4 -> "Breathe in slowly..."
                    cyclePos < 8 -> "Hold gently..."
                    else -> "Exhale and relax..."
                }
            }

            override fun onFinish() {
                binding.tvCooldownSeconds.text = "0s"
                binding.progressCooldown.progress = totalSeconds
                binding.tvBreathPhase.text = "Break complete!"

                if (prefs.isRunning) {
                    val intent = Intent(this@CooldownActivity, ScreenTimeService::class.java).apply {
                        action = ScreenTimeService.ACTION_TAKE_BREAK
                    }
                    ContextCompat.startForegroundService(this@CooldownActivity, intent)
                } else {
                    prefs.recordBreakTaken()
                    Toast.makeText(
                        this@CooldownActivity,
                        "Cooldown complete! Streak: ${prefs.currentStreak}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
