package com.screentime.bricklock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "focus_lock_channel"
    private val NOTIF_ID = 1001

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvOverlayClockTime: TextView? = null
    private var tvOverlayClockDate: TextView? = null
    private var tvOverlayRemaining: TextView? = null
    private var btnOverlayPhone: Button? = null

    private var miniReturnView: View? = null

    // 12-hour clock format (e.g., 오후 05:27:14)
    private val timeFormat = SimpleDateFormat("a hh:mm:ss", Locale.KOREAN)
    private val dateFormat = SimpleDateFormat("yyyy년 MM월 dd일 (EEE)", Locale.KOREAN)

    companion object {
        private var dialerGraceUntilMillis: Long = 0L

        fun allowDialerTemporarily(seconds: Int = 30) {
            dialerGraceUntilMillis = System.currentTimeMillis() + seconds * 1000L
        }

        fun endDialerGrace() {
            dialerGraceUntilMillis = 0L
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!LockManager.isLocked(this@LockForegroundService)) {
                removeOverlay()
                removeMiniReturnButton()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            LockManager.enforceSilentAndDnd(this@LockForegroundService)
            updateNotification()

            val inDialerGrace = System.currentTimeMillis() < dialerGraceUntilMillis
            if (inDialerGrace) {
                overlayView?.visibility = View.GONE
                ensureMiniReturnButtonShown()
            } else {
                removeMiniReturnButton()
                ensureOverlayShown()
                overlayView?.visibility = View.VISIBLE
                updateOverlayTexts()
            }

            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        ensureOverlayShown()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)

        val lockIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(lockIntent)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        removeOverlay()
        removeMiniReturnButton()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureOverlayShown() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.activity_lock_screen, null)
            view.setBackgroundColor(Color.BLACK)
            view.isFocusableInTouchMode = true
            view.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK
            }

            tvOverlayClockTime = view.findViewById(R.id.tvClockTime)
            tvOverlayClockDate = view.findViewById(R.id.tvClockDate)
            tvOverlayRemaining = view.findViewById(R.id.tvRemaining)
            btnOverlayPhone = view.findViewById(R.id.btnPhone)

            btnOverlayPhone?.setOnClickListener {
                allowDialerTemporarily(30)
                view.visibility = View.GONE
                ensureMiniReturnButtonShown()
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(dialIntent)
                } catch (_: Exception) {
                }
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            wm.addView(view, params)
            overlayView = view
            updateOverlayTexts()
        } catch (_: Exception) {
        }
    }

    private fun ensureMiniReturnButtonShown() {
        if (miniReturnView != null) return
        if (!Settings.canDrawOverlays(this)) return

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val btn = Button(this).apply {
                text = "🔒 잠금화면 복귀"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E53935"))
                textSize = 13f
                setPadding(28, 16, 28, 16)
                setOnClickListener {
                    endDialerGrace()
                    removeMiniReturnButton()
                    overlayView?.visibility = View.VISIBLE
                }
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 100
            }

            wm.addView(btn, params)
            miniReturnView = btn
        } catch (_: Exception) {
        }
    }

    private fun removeMiniReturnButton() {
        try {
            miniReturnView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        } finally {
            miniReturnView = null
        }
    }

    private fun updateOverlayTexts() {
        val now = Date()
        tvOverlayClockTime?.text = timeFormat.format(now)
        tvOverlayClockDate?.text = dateFormat.format(now)

        val remaining = LockManager.getRemainingMillis(this)
        val totalSec = remaining / 1000
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        tvOverlayRemaining?.text = String.format(
            Locale.getDefault(),
            "남은 잠금 시간  %02d:%02d:%02d",
            hrs,
            mins,
            secs
        )
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        } finally {
            overlayView = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FocusLock 잠금 서비스",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "핸드폰 잠금 타이머 유지"
                setSound(null, null)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val remaining = LockManager.getRemainingMillis(this)
        val totalSec = remaining / 1000
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        val timeText = String.format("%02d:%02d:%02d 남음", hrs, mins, secs)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LockScreenActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("FocusLock 실행 중")
            .setContentText(timeText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }
}
