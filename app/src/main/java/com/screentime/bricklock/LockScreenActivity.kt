package com.screentime.bricklock

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockScreenActivity : AppCompatActivity() {

    private lateinit var tvClockTime: TextView
    private lateinit var tvClockDate: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var btnPhone: Button

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy년 MM월 dd일 (EEE)", Locale.KOREAN)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!LockManager.isLocked(this@LockScreenActivity)) {
                val mainIntent = Intent(this@LockScreenActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(mainIntent)
                finish()
                return
            }

            val now = Date()
            tvClockTime.text = timeFormat.format(now)
            tvClockDate.text = dateFormat.format(now)

            val remaining = LockManager.getRemainingMillis(this@LockScreenActivity)
            val totalSec = remaining / 1000
            val hrs = totalSec / 3600
            val mins = (totalSec % 3600) / 60
            val secs = totalSec % 60
            tvRemaining.text = String.format(Locale.getDefault(), "남은 잠금 시간  %02d:%02d:%02d", hrs, mins, secs)

            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_lock_screen)

        hideSystemUI()

        tvClockTime = findViewById(R.id.tvClockTime)
        tvClockDate = findViewById(R.id.tvClockDate)
        tvRemaining = findViewById(R.id.tvRemaining)
        btnPhone = findViewById(R.id.btnPhone)

        btnPhone.setOnClickListener {
            openPhoneDialer()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Ignore back button while locked
            }
        })
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun openPhoneDialer() {
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialIntent)
        } catch (_: Exception) {
        }
    }
}
