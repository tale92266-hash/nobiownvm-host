package com.nobiownvm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the QEMU guest process.
 *
 * Phase 3: this service ACTUALLY launches QEMU via ProcessBuilder
 * (previously deliberately not executed). It does not fake a
 * running guest:
 * - If the binary or guest image is missing, it reports BLOCKED
 *   with the exact reason and stops itself.
 * - If userdata cannot be created (disk full), it reports
 *   BLOCKED and stops itself.
 * - If the process fails to start, the exact exception is kept
 *   in [lastStartError] and the service stops itself.
 * - If the process exits on its own, the exit code is kept in
 *   [lastExitCode] and [vmState] becomes STOPPED.
 * - Stop VM (MainActivity stopService, or onDestroy) kills the
 *   process cleanly (destroy, short wait, then destroyForcibly).
 * - After reboot it restarts ONLY if the VM was running at
 *   shutdown (BootReceiver checks the same flag MainActivity sets).
 *
 * QEMU stdout/stderr go to filesDir/guest/qemu.log (truncated on
 * each start) so a failed boot leaves an exact error to report,
 * instead of a silent crash.
 *
 * UNTESTED ON-DEVICE: launch, VNC hand-off and process lifetime
 * have never run on Sam's phone. The boot test is the verdict.
 */
class VmService : Service() {

    companion object {
        const val CHANNEL_ID = "nobi_vm_channel"
        const val NOTIFICATION_ID = 1
        const val PREFS = "nobi_vm_prefs"
        const val KEY_WAS_RUNNING = "vm_was_running"

        const val STATE_STOPPED = "Stopped"
        const val STATE_STARTING = "Starting"
        const val STATE_RUNNING = "Running"
        const val STATE_BLOCKED = "Blocked"

        @Volatile
        var vmState: String = STATE_STOPPED
            private set

        @Volatile
        var lastBlockReason: String? = null
            private set

        @Volatile
        var lastStartError: String? = null
            private set

        @Volatile
        var lastExitCode: Int? = null
            private set

        /** Kept for MainActivity's old isRunning check. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun setWasRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WAS_RUNNING, running).apply()
        }

        fun wasRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WAS_RUNNING, false)
        }
    }

    private var qemuProcess: Process? = null
    private var watcherThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(STATE_STARTING))
        } catch (e: Exception) {
            blocked("Could not start foreground service: ${e.message ?: e.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        // v5 single-instance guard: if a QEMU process is already
        // alive, do NOT start a second one — a second process would
        // fail with "Failed to get write lock" on the image files
        // (Sam's on-device qemu.log, 170 bytes) and would truncate
        // the first process's log. Just report the existing run.
        val existing = qemuProcess
        if (existing != null && existing.isAlive) {
            vmState = STATE_RUNNING
            isRunning = true
            setWasRunning(this, true)
            updateNotification(STATE_RUNNING)
            return START_STICKY
        }
        if (existing != null) {
            // Stale reference to a dead process: drop it so the
            // fresh start below cannot be mistaken for a duplicate.
            qemuProcess = null
        }
        vmState = STATE_STARTING
        lastStartError = null
        lastExitCode = null
        lastBlockReason = null

        val command = QemuManager.buildCommand(this)
        if (command == null) {
            blocked(
                if (!QemuManager.isQemuPresent(this)) {
                    "QEMU binary missing: jniLibs/arm64-v8a/libqemu.so is not present. See QEMU_BINARY.md."
                } else {
                    "Guest image missing: " + QemuManager.missingGuestFiles(this).joinToString(", ") +
                        ". Download it first (see GUEST_IMAGE.md)."
                }
            )
            return START_NOT_STICKY
        }
        if (!QemuManager.ensureQemuShare(this)) {
            blocked(
                "QEMU share data (keymaps) could not be copied from assets. " +
                    "Reinstall the app and try again."
            )
            return START_NOT_STICKY
        }
        if (!QemuManager.ensureUserdata(this)) {
            blocked(
                "Userdata disk could not be created: need at least 20 GB free " +
                    "space (16 GB guest disk + image). Free up space and try again."
            )
            return START_NOT_STICKY
        }
        val logFile = QemuManager.guestLog(this)
        try {
            if (logFile.exists()) logFile.delete()
            logFile.parentFile?.mkdirs()
            logFile.createNewFile()
        } catch (e: Exception) {
            blocked("Could not create QEMU log file: ${e.message ?: e.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        // v6: truncate the guest serial console log too, so SHOW
        // BOOT LOG shows this boot only, not a previous boot's tail.
        // QEMU's -serial file: backend writes into this same file.
        try {
            val serialFile = QemuManager.guestSerialLog(this)
            if (serialFile.exists()) serialFile.delete()
            serialFile.parentFile?.mkdirs()
            serialFile.createNewFile()
        } catch (e: Exception) {
            // Non-fatal: boot log may be empty, but QEMU can still run.
        }
        try {
            val builder = ProcessBuilder(command)
                .redirectOutput(logFile)
                .redirectErrorStream(true)
            val process = builder.start()
            qemuProcess = process
            isRunning = true
            vmState = STATE_RUNNING
            setWasRunning(this, true)
            updateNotification(STATE_RUNNING)
            // Watcher: when QEMU exits on its own, record the code
            // instead of leaving the UI claiming "Running".
            val watcher = Thread({
                try {
                    val code = process.waitFor()
                    lastExitCode = code
                    if (qemuProcess === process) {
                        qemuProcess = null
                        isRunning = false
                        vmState = STATE_STOPPED
                        setWasRunning(this@VmService, false)
                        updateNotification(STATE_STOPPED)
                    }
                } catch (e: Exception) {
                    // Service is being torn down; nothing to report.
                }
            }, "nobi-qemu-watcher")
            watcherThread = watcher
            watcher.start()
        } catch (e: Exception) {
            lastStartError = e.message ?: e.javaClass.simpleName
            blocked("QEMU failed to start: $lastStartError. See guest/qemu.log if present.")
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun blocked(reason: String) {
        lastBlockReason = reason
        vmState = STATE_BLOCKED
        isRunning = false
        setWasRunning(this, false)
        updateNotification(STATE_BLOCKED)
        stopSelf()
    }

    private fun updateNotification(state: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (e: Exception) {
            // Best effort; the service state itself is authoritative.
        }
    }

    override fun onDestroy() {
        try {
            val process = qemuProcess
            qemuProcess = null
            watcherThread?.interrupt()
            watcherThread = null
            if (process != null) {
                process.destroy()
                try {
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (e: Exception) {
                    try {
                        process.destroyForcibly()
                    } catch (ignored: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            // Best effort only.
        }
        isRunning = false
        vmState = STATE_STOPPED
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_vm_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: String): Notification {
        val text = when (state) {
            STATE_RUNNING -> getString(R.string.notification_vm_text)
            STATE_BLOCKED -> lastBlockReason ?: getString(R.string.notification_vm_text)
            else -> getString(R.string.notification_vm_text)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notification_vm_title) + " - " + state)
            .setContentText(text)
            .setOngoing(state == STATE_RUNNING || state == STATE_STARTING)
            .build()
    }
}
