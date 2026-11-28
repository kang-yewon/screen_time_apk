package com.screentime.bricklock

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.SystemClock

object LockManager {
    private const val PREF_NAME = "brick_lock_prefs"
    private const val KEY_END_TIME = "lock_end_time_millis"
    private const val KEY_REMAINING_MILLIS = "lock_remaining_millis"
    private const val KEY_PREV_RINGER = "prev_ringer_mode"
    private const val KEY_PREV_FILTER = "prev_interruption_filter"

    private var lastKnownForegroundPkg: String? = null

    /**
     * Device-encrypted storage so BootReceiver can read lock state immediately
     * during Direct Boot (LOCKED_BOOT_COMPLETED) before PIN/pattern unlock,
     * while migrating existing credential-protected data if present.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deviceContext = context.createDeviceProtectedStorageContext()
            deviceContext.moveSharedPreferencesFrom(context, PREF_NAME)
            deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        } else {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getEndTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_END_TIME, 0L)
    }

    fun isLocked(context: Context): Boolean {
        val prefs = getPrefs(context)
        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        val savedRemaining = prefs.getLong(KEY_REMAINING_MILLIS, 0L)
        if (endTime <= 0L && savedRemaining <= 0L) return false

        val now = System.currentTimeMillis()
        val computedRemaining = endTime - now

        // If wall-clock jumped ahead right after reboot or hasn't synced properly,
        // or if endTime needs to be restored from savedRemaining on boot:
        if (computedRemaining <= 0L && savedRemaining <= 0L) {
            stopLock(context)
            return false
        }
        return true
    }

    fun getRemainingMillis(context: Context): Long {
        val prefs = getPrefs(context)
        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        if (endTime <= 0L) return 0L
        val remaining = (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
        // Persist latest remaining time every tick so power-off cannot cheat or lose state
        prefs.edit().putLong(KEY_REMAINING_MILLIS, remaining).apply()
        if (remaining == 0L) {
            stopLock(context)
        }
        return remaining
    }

    /**
     * Called on device boot/reboot to ensure endTime is valid even if wall clock changed while powered off.
     */
    fun restoreLockAfterBoot(context: Context): Boolean {
        val prefs = getPrefs(context)
        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        val savedRemaining = prefs.getLong(KEY_REMAINING_MILLIS, 0L)
        if (endTime <= 0L && savedRemaining <= 0L) return false

        val now = System.currentTimeMillis()
        val wallRemaining = endTime - now

        if (wallRemaining > 0L) {
            prefs.edit().putLong(KEY_REMAINING_MILLIS, wallRemaining).apply()
            scheduleWatchdog(context)
            return true
        } else if (savedRemaining > 15_000L && wallRemaining < -86_400_000L) {
            // Clock anomaly protection
            val newEnd = now + savedRemaining
            prefs.edit().putLong(KEY_END_TIME, newEnd).apply()
            scheduleWatchdog(context)
            return true
        } else {
            stopLock(context)
            return false
        }
    }

    fun startLock(context: Context, durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        val prefs = getPrefs(context)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        prefs.edit().apply {
            putLong(KEY_END_TIME, endTime)
            putLong(KEY_REMAINING_MILLIS, durationMillis)
            putInt(KEY_PREV_RINGER, audioManager.ringerMode)
            if (notifManager.isNotificationPolicyAccessGranted) {
                putInt(KEY_PREV_FILTER, notifManager.currentInterruptionFilter)
            }
            commit() // Synchronous commit so immediate power-off still saves to disk
        }

        enforceSilentAndDnd(context)
        scheduleWatchdog(context)
        startLockServiceAndOverlay(context)
    }

    fun startLockServiceAndOverlay(context: Context) {
        try {
            val serviceIntent = Intent(context, LockForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
        }
    }

    fun scheduleWatchdog(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_WATCHDOG
            }
            val pi = PendingIntent.getBroadcast(
                context,
                777,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + 5_000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
        }
    }

    fun enforceSilentAndDnd(context: Context) {
        try {
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notifManager.isNotificationPolicyAccessGranted) {
                notifManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {
        }
    }

    fun stopLock(context: Context) {
        val prefs = getPrefs(context)
        val prevRinger = prefs.getInt(KEY_PREV_RINGER, AudioManager.RINGER_MODE_NORMAL)
        val prevFilter = prefs.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)

        prefs.edit()
            .putLong(KEY_END_TIME, 0L)
            .putLong(KEY_REMAINING_MILLIS, 0L)
            .commit()

        try {
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notifManager.isNotificationPolicyAccessGranted) {
                notifManager.setInterruptionFilter(prevFilter)
            }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = prevRinger
        } catch (_: Exception) {
        }

        try {
            context.stopService(Intent(context, LockForegroundService::class.java))
        } catch (_: Exception) {
        }
    }

    fun isUsageAccessGranted(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun getCurrentForegroundPackage(context: Context): String? {
        if (!isUsageAccessGranted(context)) return null
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 15_000L
            val events = usm.queryEvents(start, end)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }
            if (!latestPkg.isNullOrBlank()) {
                lastKnownForegroundPkg = latestPkg
            }
        } catch (_: Exception) {
        }
        return lastKnownForegroundPkg
    }

    fun isPhoneOrDialerPackage(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()

        val blockedKeywords = listOf(
            "launcher",
            "home",
            "trebuchet",
            "nexuslauncher",
            "sec.android.app.launcher",
            "settings",
            "chrome",
            "browser",
            "youtube",
            "kakao",
            "instagram"
        )
        if (blockedKeywords.any { lower.contains(it) }) {
            return false
        }

        val allowedPhoneKeywords = listOf(
            "dialer",
            "phone",
            "incallui",
            "telecom",
            "call",
            "contacts"
        )
        if (allowedPhoneKeywords.any { lower.contains(it) }) {
            return true
        }

        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            val defaultDialer = telecomManager?.defaultDialerPackage
            if (!defaultDialer.isNullOrBlank() && packageName == defaultDialer) {
                return true
            }
        } catch (_: Exception) {
        }

        return false
    }
}
