package com.airsign

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.airsign.utils.StorageHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only start the lock screen monitor service if the user has enrolled
            if (StorageHelper.isEnrolled(context)) {
                val serviceIntent = Intent(context, LockScreenService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
