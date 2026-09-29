package com.puls.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts collection after a reboot or an app update if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (prefs.collecting && !prefs.released && prefs.deviceAddress != null) HrService.start(context)
    }
}
