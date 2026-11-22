package com.screentime.bricklock

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationBlockService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!LockManager.isLocked(this)) return

        val pkg = sbn.packageName ?: return
        // Do not cancel notifications from our own app or phone/dialer/call apps
        if (LockManager.isAllowedPackage(this, pkg)) return

        try {
            cancelNotification(sbn.key)
        } catch (_: Exception) {
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (LockManager.isLocked(this)) {
            clearActiveNonCallNotifications()
        }
    }

    private fun clearActiveNonCallNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                val pkg = sbn.packageName ?: continue
                if (!LockManager.isAllowedPackage(this, pkg)) {
                    cancelNotification(sbn.key)
                }
            }
        } catch (_: Exception) {
        }
    }
}
