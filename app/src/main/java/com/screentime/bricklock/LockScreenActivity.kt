package com.screentime.bricklock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

    private lateinit var layoutClockContainer: View
    private lateinit var layoutDialerContainer: View
    private lateinit var tvClockTime: TextView
    private lateinit var tvClockDate: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var btnPhone: Button
    private lateinit var tvPhoneInput: TextView

    private val currentPhoneNumber = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    // 12-hour clock format (e.g., 오후 05:27:14)
    private val timeFormat = SimpleDateFormat("a hh:mm:ss", Locale.KOREAN)
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

        layoutClockContainer = findViewById(R.id.layoutClockContainer)
        layoutDialerContainer = findViewById(R.id.layoutDialerContainer)
        tvClockTime = findViewById(R.id.tvClockTime)
        tvClockDate = findViewById(R.id.tvClockDate)
        tvRemaining = findViewById(R.id.tvRemaining)
        btnPhone = findViewById(R.id.btnPhone)
        tvPhoneInput = findViewById(R.id.tvPhoneInput)

        btnPhone.setOnClickListener {
            currentPhoneNumber.clear()
            updatePhoneInputDisplay()
            layoutClockContainer.visibility = View.GONE
            layoutDialerContainer.visibility = View.VISIBLE
        }

        setupBuiltInDialpad()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutDialerContainer.visibility == View.VISIBLE) {
                    layoutDialerContainer.visibility = View.GONE
                    layoutClockContainer.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun setupBuiltInDialpad() {
        val digitButtons = mapOf(
            R.id.btnKey0 to "0",
            R.id.btnKey1 to "1",
            R.id.btnKey2 to "2",
            R.id.btnKey3 to "3",
            R.id.btnKey4 to "4",
            R.id.btnKey5 to "5",
            R.id.btnKey6 to "6",
            R.id.btnKey7 to "7",
            R.id.btnKey8 to "8",
            R.id.btnKey9 to "9",
            R.id.btnKeyStar to "*",
            R.id.btnKeyHash to "#"
        )

        for ((id, digit) in digitButtons) {
            findViewById<Button>(id).setOnClickListener {
                if (currentPhoneNumber.length < 15) {
                    currentPhoneNumber.append(digit)
                    updatePhoneInputDisplay()
                }
            }
        }

        findViewById<Button>(R.id.btnKeyDelete).setOnClickListener {
            if (currentPhoneNumber.isNotEmpty()) {
                currentPhoneNumber.deleteCharAt(currentPhoneNumber.length - 1)
                updatePhoneInputDisplay()
            }
        }

        findViewById<Button>(R.id.btnCancelDial).setOnClickListener {
            layoutDialerContainer.visibility = View.GONE
            layoutClockContainer.visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.btnMakeCall).setOnClickListener {
            val number = currentPhoneNumber.toString().trim()
            if (number.isEmpty()) return@setOnClickListener

            layoutDialerContainer.visibility = View.GONE
            layoutClockContainer.visibility = View.VISIBLE
            LockForegroundService.notifyCallLaunched()

            try {
                val hasCallPerm = checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val callIntent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(callIntent)
            } catch (_: Exception) {
            }
        }
    }

    private fun updatePhoneInputDisplay() {
        val raw = currentPhoneNumber.toString()
        tvPhoneInput.text = if (raw.isEmpty()) "전화번호 입력" else raw
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
}
