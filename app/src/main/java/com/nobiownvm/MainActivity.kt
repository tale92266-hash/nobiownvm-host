package com.nobiownvm

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Host shell home screen (Phase 3). Shows only facts the app can
 * actually check on this phone: QEMU binary presence, guest image
 * presence, system page size, the fixed guest config, and VM status.
 * It never claims the guest is booted when it is not.
 *
 * Phase 3 additions over the Phase 2 shell:
 * - "Download guest image" button: runs GuestImageManager on a
 *   background thread (1.6 GB, Wi-Fi recommended) with progress.
 * - The VNC container now holds a real VncView (minimal RFB
 *   client, see VncView.kt) instead of a placeholder text view.
 *   Start VM connects it to QEMU's loopback VNC server; Stop VM
 *   disconnects it.
 *
 * UNTESTED ON-DEVICE: download, launch and VNC have never run
 * on Sam's phone. The boot test is the verdict.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var qemuStatus: TextView
    private lateinit var imageStatus: TextView
    private lateinit var pagesizeStatus: TextView
    private lateinit var vmStatus: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var downloadStatus: TextView
    private lateinit var qemuLogText: TextView
    private lateinit var vncView: VncView
    private var vncConnected = false
    private var lastQemuLogText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        qemuStatus = findViewById(R.id.row_qemu_status)
        imageStatus = findViewById(R.id.row_image_status)
        pagesizeStatus = findViewById(R.id.row_pagesize_status)
        vmStatus = findViewById(R.id.row_vm_status)
        statusDot = findViewById(R.id.status_dot)
        downloadStatus = findViewById(R.id.row_download_status)
        qemuLogText = findViewById(R.id.qemu_log_text)
        vncView = findViewById(R.id.vnc_view)

        // Diagnosis only (v2): QEMU exit code 1 on Sam's phone left
        // no visible error. qemu.log already holds QEMU stdout/stderr
        // (VmService redirects there); this button surfaces it so
        // the exact failure line can be reported instead of guessed.
        // QEMU arguments are NOT changed in this build.
        findViewById<Button>(R.id.button_show_qemu_log).setOnClickListener {
            refreshQemuLog()
        }

        findViewById<Button>(R.id.button_copy_qemu_log).setOnClickListener {
            if (lastQemuLogText.isEmpty()) {
                refreshQemuLog()
                if (lastQemuLogText.isEmpty()) {
                    Toast.makeText(this, R.string.qemu_log_copy_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("QEMU log", lastQemuLogText))
            Toast.makeText(this, R.string.qemu_log_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.button_download_image).setOnClickListener {
            if (QemuManager.isGuestImagePresent(this)) {
                downloadStatus.text = getString(R.string.download_already_present)
                refreshStatus()
                return@setOnClickListener
            }
            downloadStatus.text = getString(R.string.download_starting)
            Thread({
                val result = GuestImageManager.downloadAndExtract(this) { downloaded, total, phase ->
                    runOnUiThread {
                        downloadStatus.text = if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            "$phase: $pct% (${downloaded / 1024 / 1024} MB / ${total / 1024 / 1024} MB)"
                        } else {
                            "$phase..."
                        }
                    }
                }
                runOnUiThread {
                    downloadStatus.text = result
                    refreshStatus()
                }
            }, "nobi-guest-download").start()
        }

        findViewById<Button>(R.id.button_start_vm).setOnClickListener {
            if (!QemuManager.isQemuPresent(this) || !QemuManager.isGuestImagePresent(this)) {
                Toast.makeText(this, R.string.toast_vm_blocked, Toast.LENGTH_LONG).show()
                refreshStatus()
                return@setOnClickListener
            }
            VmService.setWasRunning(this, true)
            val intent = Intent(this, VmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
            // QEMU's VNC server appears seconds after the process
            // starts; VncClient retries for 60 seconds, so connecting
            // immediately is correct.
            vncView.connect()
            vncConnected = true
            Toast.makeText(this, R.string.toast_vm_started, Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.button_stop_vm).setOnClickListener {
            vncView.disconnect()
            vncConnected = false
            VmService.setWasRunning(this, false)
            stopService(Intent(this, VmService::class.java))
            refreshStatus()
        }

        findViewById<Button>(R.id.button_guest_image_info).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.button_guest_image_info)
                .setMessage(R.string.guest_image_info_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // If the service is already running (e.g. after a rotation
        // or returning from Recents), re-attach the VNC view once.
        if (VmService.vmState == VmService.STATE_RUNNING && !vncConnected) {
            vncView.connect()
            vncConnected = true
        }
        refreshStatus()
    }

    override fun onDestroy() {
        vncView.disconnect()
        super.onDestroy()
    }

    private fun setStatus(view: TextView, text: String, good: Boolean) {
        view.text = text
        val color = if (good) R.color.status_good else R.color.status_bad
        view.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun refreshQemuLog() {
        qemuLogText.text = getString(R.string.qemu_log_empty)
        Thread({
            val logFile = QemuManager.guestLog(this)
            val header = StringBuilder()
            header.append("VM state: ").append(VmService.vmState).append("\n")
            VmService.lastExitCode?.let { header.append("QEMU exit code: ").append(it).append("\n") }
            VmService.lastStartError?.let { header.append("Start error: ").append(it).append("\n") }
            VmService.lastBlockReason?.let { header.append("Block reason: ").append(it).append("\n") }
            val body: String = if (!logFile.exists()) {
                getString(R.string.qemu_log_empty)
            } else {
                try {
                    val bytes = logFile.readBytes()
                    var text = String(bytes, Charsets.UTF_8)
                    if (text.length > 12000) {
                        text = "... (showing last 12000 chars of " + bytes.size + " bytes)\n" +
                            text.substring(text.length - 12000)
                    }
                    "Log file: " + logFile.absolutePath + " (" + bytes.size + " bytes)\n\n" + text
                } catch (e: Exception) {
                    "Could not read QEMU log: " + (e.message ?: e.javaClass.simpleName)
                }
            }
            val full = header.toString() + "\n" + body
            lastQemuLogText = full
            runOnUiThread { qemuLogText.text = full }
        }, "nobi-qemu-log-read").start()
    }

    private fun refreshStatus() {
        val qemuOk = QemuManager.isQemuPresent(this)
        setStatus(
            qemuStatus,
            if (qemuOk) getString(R.string.status_present) else getString(R.string.status_missing),
            qemuOk
        )

        val imageOk = QemuManager.isGuestImagePresent(this)
        setStatus(
            imageStatus,
            if (imageOk) getString(R.string.status_present) else getString(R.string.status_missing),
            imageOk
        )

        val pageSize = QemuManager.pageSizeBytes()
        if (pageSize == 16384L) {
            // 16 KB pages: the QEMU .so MUST be 16 KB-page aligned.
            // The shipped binary was verified with readelf on the
            // build machine (LOAD alignment 0x4000 on all segments),
            // so this is a warning to record, not a blocker.
            setStatus(pagesizeStatus, "16384 - QEMU .so verified 16KB-aligned at build", true)
        } else if (pageSize > 0) {
            setStatus(pagesizeStatus, pageSize.toString(), true)
        } else {
            setStatus(pagesizeStatus, "Unknown", false)
        }

        val state = VmService.vmState
        val detail = when (state) {
            VmService.STATE_BLOCKED -> VmService.lastBlockReason ?: ""
            VmService.STATE_STOPPED -> VmService.lastExitCode?.let { "QEMU exited with code $it" } ?: ""
            else -> ""
        }
        setStatus(
            vmStatus,
            if (detail.isEmpty()) state else "$state: $detail",
            state == VmService.STATE_RUNNING
        )
        // Status dot: green = Running, red = Stopped/Failed/Blocked,
        // grey = Starting/idle.
        val dot = when (state) {
            VmService.STATE_RUNNING -> R.drawable.dot_green
            VmService.STATE_STARTING -> R.drawable.dot_grey
            else -> R.drawable.dot_red
        }
        statusDot.setBackgroundResource(dot)
    }
}
