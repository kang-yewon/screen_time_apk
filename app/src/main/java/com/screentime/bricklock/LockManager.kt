package com.screentime.bricklock

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Process

object LockManager {
    private const val PREF_NAME = "brick_lock_prefs"
    private const val KEY_END_TIME = "lock_end_time_millis"
    private const val KEY_PREV_RINGER = "prev_ringer_mode"
    private const val KEY_PREV_FILTER = "prev_interruption_filter"

    private var lastKnownForegroundPkg: String? = null

    fun getEndTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_END_TIME, 0L)
    }

    fun isLocked(context: Context): Boolean {
        val endTime = getEndTime(context)
        if (endTime <= 0L) return false
        val remaining = endTime - System.currentTimeMillis()
        if (remaining <= 0L) {
            stopLock(context)
            return false
        }
        return true
    }

    fun getRemainingMillis(context: Context): Long {
        val endTime = getEndTime(context)
        if (endTime <= 0L) return 0L
        return (endTime - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun startLock(context: Context, durationMillis: Long) {
        val endTime = System.currentTimeMillis() + durationMillis
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        prefs.edit().apply {
            putLong(KEY_END_TIME, endTime)
            putInt(KEY_PREV_RINGER, audioManager.ringerMode)
            if (notifManager.isNotificationPolicyAccessGranted) {
                putInt(KEY_PREV_FILTER, notifManager.currentInterruptionFilter)
            }
            apply()
        }

        enforceSilentAndDnd(context)

        val serviceIntent = Intent(context, LockForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
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
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prevRinger = prefs.getInt(KEY_PREV_RINGER, AudioManager.RINGER_MODE_NORMAL)
        val prevFilter = prefs.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)

        prefs.edit().putLong(KEY_END_TIME, 0L).apply()

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

        // Exclude launchers / home screen / settings / browsers explicitly
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
