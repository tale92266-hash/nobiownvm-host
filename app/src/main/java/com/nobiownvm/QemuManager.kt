package com.nobiownvm

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * QEMU launch data for the iQOO Z10 5G ONLY. Phone-only, fixed values
 * from Phase 1 (README_PHASE1.md + host-skeleton/qemu-command.txt).
 *
 * Phase 3 status: the verified arm64-v8a Bionic-linked binary IS
 * now placed as jniLibs/arm64-v8a/libqemu.so (see QEMU_BINARY.md),
 * and VmService actually launches it via ProcessBuilder. The
 * guest image, VNC client and launch wiring are implemented in
 * this phase — but NONE of it has run on Sam's phone yet.
 * The boot test is the verdict.
 */
object QemuManager {

    const val GUEST_RAM_MB = 4096
    const val GUEST_VCPU = 4
    const val GUEST_DISK_GB = 16
    const val GUEST_CPU = "max,pauth-impdef=on"

    // The binary, once packaged, lands in the app native library dir.
    // It MUST be named libqemu.so so Android installs it with an
    // exec_type SELinux label (see QEMU_BINARY.md). A copy in app
    // private storage cannot be exec'd on Android 10+.
    fun qemuBinaryFile(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, "libqemu.so")
    }

    fun isQemuPresent(context: Context): Boolean {
        val f = qemuBinaryFile(context)
        return f.exists() && f.canExecute()
    }

    fun guestDir(context: Context): File {
        return File(context.filesDir, "guest")
    }

    /** QEMU data dir (keymaps etc.) in private storage, copied from assets. */
    fun qemuShareDir(context: Context): File {
        return File(context.filesDir, "qemu-share")
    }

    /**
     * Copy the real QEMU en-us keymap from assets to private storage.
     * Needed because the minimal QEMU build ships no datadir: without
     * it, VNC fails with "could not read keymap file: 'en-us'".
     * Returns false only if the copy itself fails.
     */
    fun ensureQemuShare(context: Context): Boolean {
        return try {
            val dest = File(qemuShareDir(context), "keymaps/en-us")
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open("qemu-share/keymaps/en-us").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            dest.exists()
        } catch (e: Exception) {
            false
        }
    }

    fun guestKernel(context: Context): File = File(guestDir(context), "kernel-ranchu")
    fun guestRamdisk(context: Context): File = File(guestDir(context), "ramdisk.img")
    fun guestSystem(context: Context): File = File(guestDir(context), "system.img")
    fun guestUserdata(context: Context): File = File(guestDir(context), "userdata-16G.img")

    /** QEMU stdout/stderr log (truncated on each Start VM). */
    fun guestLog(context: Context): File = File(guestDir(context), "qemu.log")

    /**
     * Create the 16 GB userdata disk on first boot only.
     * Uses a sparse file (setLength) so it does not immediately
     * write 16 GB of zeros; QEMU allocates blocks as the guest
     * writes. Returns false if there is not enough free space
     * (20 GB headroom: 16 GB disk + image + host breathing room)
     * or if creation fails — callers must then stay BLOCKED.
     */
    fun ensureUserdata(context: Context): Boolean {
        val file = guestUserdata(context)
        if (file.exists()) return true
        val guest = guestDir(context)
        if (!guest.exists()) guest.mkdirs()
        val free = try {
            android.os.StatFs(guest.absolutePath).availableBytes
        } catch (e: Exception) {
            -1L
        }
        if (free in 0 until 20L * 1024L * 1024L * 1024L) return false
        return try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(16L * 1024L * 1024L * 1024L)
            }
            true
        } catch (e: Exception) {
            try {
                file.delete()
            } catch (ignored: Exception) {
            }
            false
        }
    }

    fun isGuestImagePresent(context: Context): Boolean {
        // userdata is created on first boot; kernel + ramdisk + system
        // must already be present from the first-run download.
        return guestKernel(context).exists() &&
            guestRamdisk(context).exists() &&
            guestSystem(context).exists()
    }

    fun missingGuestFiles(context: Context): List<String> {
        val out = mutableListOf<String>()
        if (!guestKernel(context).exists()) out.add("kernel-ranchu")
        if (!guestRamdisk(context).exists()) out.add("ramdisk.img")
        if (!guestSystem(context).exists()) out.add("system.img")
        if (!guestUserdata(context).exists()) out.add("userdata-16G.img (created on first boot)")
        return out
    }

    /** System page size in bytes, or -1 if it cannot be read. */
    fun pageSizeBytes(): Long {
        return try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Exact QEMU command from host-skeleton/qemu-command.txt, with
     * host-private-dir paths substituted. Returns null if the binary
     * or the guest image is missing - the caller must NOT try to run
     * a partial command.
     *
     * v3 FIX (2026-10-25, from Sam's on-device qemu.log, 88 bytes):
     * "-device virtio-net-pci,netdev=net0: failed to find romfile
     * efi-virtio.rom" — QEMU's virtio PCI devices try to load an
     * option ROM from QEMU's datadir, which the minimal build does
     * NOT ship (no QEMU ROM files in the APK), so the device
     * fails before the guest boots (exit code 1). Every virtio
     * PCI device below therefore sets romfile= (empty) to disable
     * ROM loading. Block devices use explicit -drive id=...+
     * -device virtio-blk-pci so romfile= can be set on them too.
     * Do NOT reintroduce if=virtio shorthand drives.
     *
     * v5 FIX (2026-10-25, from Sam's on-device qemu.log, 170 bytes):
     * "-device virtio-blk-pci,drive=hd0,romfile=: Failed to get
     * write lock. Is another process using the image
     * [.../guest/system.img]?" — QEMU opens drives read-write by
     * default and takes an exclusive write lock. The guest system
     * partition must be read-only anyway (as in the Android
     * emulator): the guest never writes it, userdata is the
     * writable drive. So system.img is attached with readonly=on.
     * userdata-16G.img stays read-write — do NOT make it readonly.
     * VmService also refuses a second QEMU process while one is
     * alive, so a double-Start tap cannot create this lock clash.
     *
     * KNOWN GAP (found in Phase 3 by listing the verified zip):
     * the API 34 image also ships vendor.img (100 MB) and
     * encryptionkey.img, which this command does NOT attach.
     * The Android emulator normally supplies a vendor partition;
     * if the guest fails to mount /vendor or fails encryption
     * setup, attaching vendor.img as an additional virtio drive
     * (drive order to be determined from the failure, NOT guessed
     * blindly) is the first fix to try. vendor.img is already
     * extracted by GuestImageManager so no re-download is needed.
     * Likewise, userdata is a fresh 16 GB sparse file here, not
     * the zip's 1 MB userdata.img template — if the guest cannot
     * mount/format /data on first boot, that template is the
     * fallback to investigate. Both are UNTESTED until the boot test.
     */
    fun buildCommand(context: Context): List<String>? {
        if (!isQemuPresent(context)) return null
        if (!isGuestImagePresent(context)) return null
        val guest = guestDir(context).absolutePath
        return listOf(
            qemuBinaryFile(context).absolutePath,
            "-machine", "virt,accel=tcg",
            "-cpu", GUEST_CPU,
            "-smp", GUEST_VCPU.toString(),
            "-m", GUEST_RAM_MB.toString(),
            "-kernel", "$guest/kernel-ranchu",
            "-initrd", "$guest/ramdisk.img",
            "-append", "console=ttyAMA0 androidboot.hardware=ranchu",
            "-drive", "file=$guest/system.img,format=raw,id=hd0,if=none,readonly=on",
            "-device", "virtio-blk-pci,drive=hd0,romfile=",
            "-drive", "file=$guest/userdata-16G.img,format=raw,id=hd1,if=none",
            "-device", "virtio-blk-pci,drive=hd1,romfile=",
            "-device", "virtio-gpu-pci,romfile=",
            // Data dir for keymaps (copied from assets by ensureQemuShare):
            // without -L, VNC fails with "could not read keymap file: 'en-us'".
            "-L", qemuShareDir(context).absolutePath,
            // Bind VNC to loopback only: the guest screen must be
            // viewable from this app, never from the network.
            "-display", "vnc=127.0.0.1:0",
            "-netdev", "user,id=net0",
            "-device", "virtio-net-pci,netdev=net0,romfile=",
            "-serial", "none",
            "-monitor", "none"
        )
    }
}
