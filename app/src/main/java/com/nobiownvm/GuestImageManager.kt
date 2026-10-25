package com.nobiownvm

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Guest image acquisition for the iQOO Z10 5G ONLY.
 *
 * Chosen image (verified against Google's official SDK repository
 * XML, https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-1.xml,
 * on 2026-10-25 — NOT from memory, NOT from a MOD site):
 * - Package: system-images;android-34;google_apis;arm64-v8a
 * - Display name: "Google APIs ARM 64 v8a System Image", revision 14
 * - Zip: arm64-v8a-34_r14.zip
 * - URL: https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-34_r14.zip
 * - Size: 1,610,393,229 bytes (about 1.50 GiB)
 * - Checksum in the repository XML: SHA-1
 *   2fe8b46d419a3400e30f31b0152b241b50c8b99f
 * - NOT google_apis_playstore (Play updaters waste TCG CPU/RAM).
 * - API 33 alternative (same XML): arm64-v8a-33_r17.zip,
 *   1,761,582,980 bytes, SHA-1
 *   47516ccb88230c42965940a929f51053562ee351.
 *
 * Files the host needs from the zip (matched by file NAME, not
 * by a guessed zip path — the emulator zip layout is verified
 * at download time, never assumed):
 * - kernel-ranchu, ramdisk.img, system.img -> filesDir/guest/
 *
 * The image is NEVER bundled in the APK. It is downloaded on
 * first run into filesDir/guest-download/, verified (size +
 * SHA-1), then extracted. If the download cannot be verified,
 * the partial files are deleted and Start VM stays BLOCKED.
 *
 * Manual placement path (fallback, same verification):
 * if kernel-ranchu + ramdisk.img + system.img are already in
 * filesDir/guest/ (placed by the tester with a file manager or
 * adb), QemuManager.isGuestImagePresent() is already true and
 * no download is needed. The download button is then a no-op
 * that reports the files are already present.
 *
 * Multilib gate (UNVERIFIED until the guest boots): the guest
 * Settings > About phone ABI list MUST include armeabi-v7a
 * or Sam's 32-bit Instagram APK cannot run. This manager does
 * NOT claim multilib — the boot test checks it.
 *
 * UNTESTED ON-DEVICE: download, unzip and userdata creation
 * have never run on Sam's phone. The boot test is the verdict.
 */
object GuestImageManager {

    const val PACKAGE = "system-images;android-34;google_apis;arm64-v8a"
    const val IMAGE_URL =
        "https://dl.google.com/android/repository/sys-img/google_apis/arm64-v8a-34_r14.zip"
    const val IMAGE_SIZE_BYTES = 1610393229L
    const val IMAGE_SHA1 = "2fe8b46d419a3400e30f31b0152b241b50c8b99f"
    const val USERDATA_SIZE_BYTES = 16L * 1024L * 1024L * 1024L

    // vendor.img is extracted too (it exists in the verified zip),
    // and since v8 so is encryptionkey.img: despite its name, its
    // GPT contains exactly one partition, named "metadata", and
    // QemuManager attaches it as the /metadata disk — without it,
    // init first stage waits forever for "metadata" (Sam's v7
    // serial log, 2026-10-25). Extracting it now means fresh
    // installs never need another 1.6 GB download for it.
    private val WANTED_FILES =
        setOf("kernel-ranchu", "ramdisk.img", "system.img", "vendor.img", "encryptionkey.img")

    fun downloadDir(context: Context): File = File(context.filesDir, "guest-download")
    fun downloadZip(context: Context): File = File(downloadDir(context), "arm64-v8a-34_r14.zip")

    /**
     * Download + verify + extract. Runs on the caller's thread —
     * callers MUST run this on a background thread. Progress is
     * reported as (downloadedBytes, totalBytes, phase) where
     * phase is "download", "verify", or "extract".
     * Returns a human-readable result; success means all three
     * guest files are now in filesDir/guest/.
     */
    fun downloadAndExtract(
        context: Context,
        onProgress: (downloaded: Long, total: Long, phase: String) -> Unit
    ): String {
        if (QemuManager.isGuestImagePresent(context)) {
            return "Guest image already present — no download needed."
        }
        val dir = downloadDir(context)
        if (!dir.exists()) dir.mkdirs()
        val zip = downloadZip(context)
        try {
            // Phase 1: download (fresh; partial downloads are not resumed,
            // because a resumed file that fails SHA-1 would be ambiguous).
            if (zip.exists()) zip.delete()
            val connection = URL(IMAGE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return "Download failed: HTTP ${connection.responseCode}. " +
                    "Check Wi-Fi and try again."
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: IMAGE_SIZE_BYTES
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(zip).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total, "download")
                    }
                }
            }
            connection.disconnect()

            // Phase 2: verify size + SHA-1 against the repository XML.
            onProgress(downloaded, total, "verify")
            if (zip.length() != IMAGE_SIZE_BYTES) {
                zip.delete()
                return "Download failed verification: size ${zip.length()} " +
                    "!= expected $IMAGE_SIZE_BYTES. Partial file deleted."
            }
            val sha1 = sha1Hex(zip)
            if (!sha1.equals(IMAGE_SHA1, ignoreCase = true)) {
                zip.delete()
                return "Download failed verification: SHA-1 $sha1 " +
                    "!= expected $IMAGE_SHA1. Partial file deleted."
            }

            // Phase 3: extract only the three files we need, matched
            // by basename so a changed zip prefix cannot break us.
            onProgress(0, 0, "extract")
            val guest = QemuManager.guestDir(context)
            if (!guest.exists()) guest.mkdirs()
            val found = mutableSetOf<String>()
            ZipInputStream(FileInputStream(zip)).use { zin ->
                var entry: ZipEntry?
                while (zin.nextEntry.also { entry = it } != null) {
                    val name = entry!!.name.substringAfterLast('/')
                    if (name in WANTED_FILES && !entry!!.isDirectory) {
                        FileOutputStream(File(guest, name)).use { out ->
                            zin.copyTo(out)
                        }
                        found.add(name)
                    }
                }
            }
            zip.delete()
            val missing = WANTED_FILES - found
            return if (missing.isEmpty()) {
                "Guest image ready: kernel-ranchu, ramdisk.img, system.img, vendor.img, encryptionkey.img extracted."
            } else {
                "Download verified but zip is missing: ${missing.joinToString(", ")}. " +
                    "Do not start the VM — report this exact list."
            }
        } catch (e: Exception) {
            try {
                zip.delete()
            } catch (ignored: Exception) {
            }
            return "Guest image download failed: ${e.message ?: e.javaClass.simpleName}. " +
                "Partial file deleted; Start VM stays BLOCKED."
        }
    }

    private fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
