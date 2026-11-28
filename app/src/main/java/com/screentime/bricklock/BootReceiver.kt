package com.screentime.bricklock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WATCHDOG = "com.screentime.focuslock.ACTION_WATCHDOG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (isBootEvent || action == ACTION_WATCHDOG) {
            val active = if (isBootEvent) {
                LockManager.restoreLockAfterBoot(context)
            } else {
                LockManager.isLocked(context)
            }

            if (active) {
                LockManager.enforceSilentAndDnd(context)
                LockManager.startLockServiceAndOverlay(context)
                LockManager.scheduleWatchdog(context)
            }
        }
    }
}
