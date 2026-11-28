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
import android.net.Uri
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

    private var layoutClockContainer: View? = null
    private var layoutDialerContainer: View? = null

    private var tvOverlayClockTime: TextView? = null
    private var tvOverlayClockDate: TextView? = null
    private var tvOverlayRemaining: TextView? = null
    private var btnOverlayPhone: Button? = null

    private var tvPhoneInput: TextView? = null
    private var currentPhoneNumber = StringBuilder()

    private var miniReturnView: View? = null

    // 12-hour clock format (e.g., 오후 05:27:14)
    private val timeFormat = SimpleDateFormat("a hh:mm:ss", Locale.KOREAN)
    private val dateFormat = SimpleDateFormat("yyyy년 MM월 dd일 (EEE)", Locale.KOREAN)

    companion object {
        private var dialerLaunchedAtMillis: Long = 0L

        fun notifyCallLaunched() {
            dialerLaunchedAtMillis = System.currentTimeMillis()
        }

        fun endDialerGrace() {
            dialerLaunchedAtMillis = 0L
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
            LockManager.scheduleWatchdog(this@LockForegroundService)
            updateNotification()

            val shouldAllowPhoneScreen = shouldHideOverlayForPhoneCall()
            if (shouldAllowPhoneScreen) {
                overlayView?.visibility = View.GONE
                ensureMiniReturnButtonShown()
            } else {
                removeMiniReturnButton()
                ensureOverlayShown()
                overlayView?.visibility = View.VISIBLE
                updateOverlayTexts()
            }

            // Fast 200ms check so leaving phone app to home/other apps immediately covers the screen
            handler.postDelayed(this, 200L)
        }
    }

    private fun shouldHideOverlayForPhoneCall(): Boolean {
        val elapsedSinceLaunch = System.currentTimeMillis() - dialerLaunchedAtMillis
        if (dialerLaunchedAtMillis <= 0L) return false

        // Give 1.5s transition time right after pressing Call button for phone app to come to foreground
        if (elapsedSinceLaunch < 1500L) {
            return true
        }

        if (LockManager.isUsageAccessGranted(this)) {
            val fgPkg = LockManager.getCurrentForegroundPackage(this)
            val isPhoneFg = LockManager.isPhoneOrDialerPackage(this, fgPkg)
            if (!isPhoneFg) {
                // User pressed Home or switched to another app -> immediately re-lock!
                dialerLaunchedAtMillis = 0L
                return false
            }
            return true
        } else {
            // Fallback if usage access not granted: only allow 8 seconds
            if (elapsedSinceLaunch > 8000L) {
                dialerLaunchedAtMillis = 0L
                return false
            }
            return true
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

            layoutClockContainer = view.findViewById(R.id.layoutClockContainer)
            layoutDialerContainer = view.findViewById(R.id.layoutDialerContainer)

            tvOverlayClockTime = view.findViewById(R.id.tvClockTime)
            tvOverlayClockDate = view.findViewById(R.id.tvClockDate)
            tvOverlayRemaining = view.findViewById(R.id.tvRemaining)
            btnOverlayPhone = view.findViewById(R.id.btnPhone)
            tvPhoneInput = view.findViewById(R.id.tvPhoneInput)

            btnOverlayPhone?.setOnClickListener {
                // Show built-in dialpad inside the lock overlay so user cannot escape to home screen
                currentPhoneNumber.clear()
                updatePhoneInputDisplay()
                layoutClockContainer?.visibility = View.GONE
                layoutDialerContainer?.visibility = View.VISIBLE
            }

            setupBuiltInDialpad(view)

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

    private fun setupBuiltInDialpad(root: View) {
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
            root.findViewById<Button>(id)?.setOnClickListener {
                if (currentPhoneNumber.length < 15) {
                    currentPhoneNumber.append(digit)
                    updatePhoneInputDisplay()
                }
            }
        }

        root.findViewById<Button>(R.id.btnKeyDelete)?.setOnClickListener {
            if (currentPhoneNumber.isNotEmpty()) {
                currentPhoneNumber.deleteCharAt(currentPhoneNumber.length - 1)
                updatePhoneInputDisplay()
            }
        }

        root.findViewById<Button>(R.id.btnCancelDial)?.setOnClickListener {
            layoutDialerContainer?.visibility = View.GONE
            layoutClockContainer?.visibility = View.VISIBLE
        }

        root.findViewById<Button>(R.id.btnMakeCall)?.setOnClickListener {
            val number = currentPhoneNumber.toString().trim()
            if (number.isEmpty()) return@setOnClickListener

            layoutDialerContainer?.visibility = View.GONE
            layoutClockContainer?.visibility = View.VISIBLE
            notifyCallLaunched()
            overlayView?.visibility = View.GONE
            ensureMiniReturnButtonShown()

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
        tvPhoneInput?.text = if (raw.isEmpty()) "전화번호 입력" else raw
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
