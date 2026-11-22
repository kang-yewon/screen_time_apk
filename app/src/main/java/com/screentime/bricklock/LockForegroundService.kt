package com.screentime.bricklock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class LockForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val CHANNEL_ID = "brick_lock_channel"
    private val NOTIF_ID = 1001

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!LockManager.isLocked(this@LockForegroundService)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            LockManager.enforceSilentAndDnd(this@LockForegroundService)
            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "벽돌 잠금 서비스",
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
            .setContentTitle("벽돌 모드 실행 중")
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
