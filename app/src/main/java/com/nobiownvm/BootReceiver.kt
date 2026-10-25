package com.nobiownvm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restarts the VM service after reboot ONLY if the VM was running
 * when the phone shut down. It never starts a VM the user did not
 * already start, and it cannot start a guest without the QEMU
 * binary + guest image (VmService reports BLOCKED in that case).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VmService.wasRunning(context)) return
        val serviceIntent = Intent(context, VmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
