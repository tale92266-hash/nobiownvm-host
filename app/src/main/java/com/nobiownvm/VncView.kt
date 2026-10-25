package com.nobiownvm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.math.roundToInt

/**
 * Minimal RFB (VNC) client view, written for this project — no
 * third-party VNC library, no closed-source binary.
 *
 * Protocol subset (RFC 6143), chosen for correctness over speed:
 * - Version 3.8 handshake, security type None (1) only. QEMU is
 *   launched with no VNC password, so any other security type is
 *   a hard error, never silently downgraded or bypassed.
 * - Pixel format: 32 bpp, depth 24, true colour, little-endian
 *   is requested via SetPixelFormat and the decoder decodes
 *   little-endian. QEMU honours SetPixelFormat; if a server did
 *   not, frames would decode with swapped channels — that case
 *   is NOT handled, and would show up immediately in the boot
 *   test as wrong colours, not as a silent success.
 * - Encodings requested: Raw (0) and CopyRect (1) only. QEMU
 *   sends only changed rectangles inside a FramebufferUpdate,
 *   so Raw is usable for a first working client even though a
 *   full-frame refresh is large. Hextile/ZRLE are NOT claimed.
 * - Pointer events are forwarded (touch -> guest coordinates,
 *   scaled from view size to framebuffer size). Key events are
 *   NOT implemented in this phase — taps are enough for the
 *   locked launcher; a keyboard mapping is a later step if the
 *   boot test passes and typing inside the guest is needed.
 *
 * Threading: all socket I/O runs on one background thread.
 * Bitmap updates are posted to the UI thread. Touch events are
 * queued to the same background thread via the client object.
 *
 * UNTESTED ON-DEVICE: this client has never talked to a real
 * QEMU VNC server on Sam's phone. The boot test is the verdict.
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    var statusText: String = ""
        private set

    // v6 diagnostics: the "Display output is not active." frame is a
    // real framebuffer from QEMU, so a bitmap alone does not prove
    // the guest reached graphics. Record frame size + last frame
    // time so MainActivity can show "connected but no scanout"
    // vs "not connected at all" distinctly.
    @Volatile
    var lastFrameWidth: Int = 0
        private set

    @Volatile
    var lastFrameHeight: Int = 0
        private set

    @Volatile
    var lastFrameAtMs: Long = 0L
        private set

    @Volatile
    var frameCount: Long = 0L
        private set

    /** One-line diagnostic for the status area, never empty. */
    fun diagnostics(): String {
        val status = statusText
        if (frameCount == 0L) {
            return if (status.isEmpty()) "VNC: no frames yet" else status
        }
        val ageSec = (System.currentTimeMillis() - lastFrameAtMs) / 1000
        return "VNC: ${lastFrameWidth}x$lastFrameHeight, $frameCount frames, last frame ${ageSec}s ago"
    }

    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var client: VncClient? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }

    fun connect(host: String = "127.0.0.1", port: Int = 5900) {
        disconnect()
        statusText = context.getString(R.string.vnc_connecting)
        lastFrameWidth = 0
        lastFrameHeight = 0
        lastFrameAtMs = 0L
        frameCount = 0L
        val c = VncClient(host, port,
            onFrame = { bmp ->
                lastFrameWidth = bmp.width
                lastFrameHeight = bmp.height
                lastFrameAtMs = System.currentTimeMillis()
                frameCount++
                synchronized(lock) {
                    bitmap?.recycle()
                    bitmap = bmp
                }
                postInvalidate()
            },
            onStatus = { s ->
                statusText = s
                postInvalidate()
            })
        client = c
        Thread(c, "nobi-vnc").start()
    }

    fun disconnect() {
        try {
            client?.stop()
        } catch (e: Exception) {
            // Best effort.
        }
        client = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val bmp = synchronized(lock) { bitmap }
        if (bmp != null && !bmp.isRecycled) {
            // Fit the guest framebuffer inside this view, centred.
            val vw = width.toFloat()
            val vh = height.toFloat()
            if (vw > 0 && vh > 0) {
                val scale = minOf(vw / bmp.width, vh / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = (vw - dw) / 2f
                val top = (vh - dh) / 2f
                canvas.save()
                canvas.translate(left, top)
                canvas.scale(scale, scale)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                canvas.restore()
            }
        } else {
            val msg = statusText.ifEmpty {
                context.getString(R.string.vnc_placeholder_text)
            }
            canvas.drawText(msg, width / 2f, height / 2f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return false
        val bmp = synchronized(lock) { bitmap } ?: return false
        // Map the touch point back through the same fit transform
        // used in onDraw.
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return false
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        val gx = ((event.x - left) / scale).roundToInt().coerceIn(0, bmp.width - 1)
        val gy = ((event.y - top) / scale).roundToInt().coerceIn(0, bmp.height - 1)
        val down = event.action == MotionEvent.ACTION_DOWN ||
            event.action == MotionEvent.ACTION_MOVE
        c.sendPointer(gx, gy, down)
        return true
    }

    override fun onDetachedFromWindow() {
        disconnect()
        super.onDetachedFromWindow()
    }
}

/**
 * Blocking RFB client. One thread, one socket, no hidden retries
 * beyond the initial connect loop: QEMU's VNC server appears a
 * few seconds after the process starts, so connect() retries for
 * up to 60 seconds before reporting failure.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) : Runnable {

    @Volatile
    private var stopped = false
    private var out: DataOutputStream? = null

    @Volatile
    private var framebufferWidth = 0
    @Volatile
    private var framebufferHeight = 0
    private var pixels: IntArray = IntArray(0)
    private var pfBigEndian = false
    private var pfRedShift = 16
    private var pfGreenShift = 8
    private var pfBlueShift = 0

    fun stop() {
        stopped = true
        try {
            out?.close()
        } catch (e: Exception) {
            // Best effort.
        }
    }

    fun sendPointer(x: Int, y: Int, down: Boolean) {
        try {
            val o = out ?: return
            synchronized(o) {
                o.writeByte(5) // PointerEvent
                o.writeByte(if (down) 1 else 0) // button 1 mask
                o.writeShort(x)
                o.writeShort(y)
                o.flush()
            }
        } catch (e: Exception) {
            // Socket may be closing; the read loop reports it.
        }
    }

    override fun run() {
        var socket: Socket? = null
        try {
            // Retry loop: QEMU starts, then its VNC server binds.
            val deadline = System.currentTimeMillis() + 60_000
            var lastError: Exception? = null
            while (!stopped && System.currentTimeMillis() < deadline) {
                try {
                    socket = Socket(host, port)
                    break
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(1000)
                }
            }
            if (socket == null || stopped) {
                onStatus("VNC: could not connect (${lastError?.message ?: "stopped"})")
                return
            }
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            out = output

            // 1. Version handshake (12 bytes, "RFB 003.008\n").
            val serverVersion = ByteArray(12)
            input.readFully(serverVersion)
            val versionText = String(serverVersion, Charsets.US_ASCII)
            if (!versionText.startsWith("RFB 003.")) {
                onStatus("VNC: unsupported server version: $versionText")
                return
            }
            output.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
            output.flush()

            // 2. Security types (3.8 style: count + list).
            val count = input.readUnsignedByte()
            if (count == 0) {
                onStatus("VNC: server refused connection (0 security types)")
                return
            }
            val types = IntArray(count) { input.readUnsignedByte() }
            if (!types.contains(1)) {
                onStatus("VNC: server requires auth (types ${types.joinToString()}); only None is supported")
                return
            }
            output.writeByte(1) // None
            output.flush()
            val securityResult = input.readInt()
            if (securityResult != 0) {
                onStatus("VNC: security handshake failed ($securityResult)")
                return
            }

            // 3. ClientInit: shared desktop = 1.
            output.writeByte(1)
            output.flush()

            // 4. ServerInit.
            framebufferWidth = input.readUnsignedShort()
            framebufferHeight = input.readUnsignedShort()
            val bpp = input.readUnsignedByte()
            val depth = input.readUnsignedByte()
            pfBigEndian = input.readUnsignedByte() != 0
            input.readUnsignedByte() // true-colour flag (must be 1 for us)
            val redMax = input.readUnsignedShort()
            val greenMax = input.readUnsignedShort()
            val blueMax = input.readUnsignedShort()
            pfRedShift = input.readUnsignedByte()
            pfGreenShift = input.readUnsignedByte()
            pfBlueShift = input.readUnsignedByte()
            input.skipBytes(3) // padding
            val nameLen = input.readInt()
            input.skipBytes(nameLen)
            if (bpp != 32 || depth != 24) {
                // We requested 32/24 below via SetPixelFormat; if the
                // server still reports otherwise, decoding would be
                // wrong, so fail loudly instead of showing garbage.
                onStatus("VNC: unexpected pixel format bpp=$bpp depth=$depth")
                return
            }
            @Suppress("UNUSED_VARIABLE")
            val unused = redMax + greenMax + blueMax
            pixels = IntArray(framebufferWidth * framebufferHeight)

            // 5. SetPixelFormat to 32bpp/24 depth, little-endian,
            //    true colour, shifts 16/8/0 (standard ARGB layout).
            output.writeByte(0) // SetPixelFormat
            output.writeByte(0); output.writeByte(0); output.writeByte(0) // padding
            output.writeByte(32); output.writeByte(24); output.writeByte(0)
            output.writeByte(1) // true colour
            output.writeShort(255); output.writeShort(255); output.writeShort(255)
            output.writeByte(16); output.writeByte(8); output.writeByte(0)
            output.writeByte(0); output.writeByte(0); output.writeByte(0) // padding
            output.flush()
            // From here on, decode using OUR requested format, not
            // the server's original one (server must honour it).
            pfBigEndian = false
            pfRedShift = 16
            pfGreenShift = 8
            pfBlueShift = 0

            // 6. SetEncodings: Raw (0), CopyRect (1).
            output.writeByte(2) // SetEncodings
            output.writeByte(0) // padding
            output.writeShort(2) // count
            output.writeInt(0) // Raw
            output.writeInt(1) // CopyRect
            output.flush()

            onStatus("VNC: connected ${framebufferWidth}x$framebufferHeight")
            var incremental = false
            while (!stopped) {
                // FramebufferUpdateRequest
                output.writeByte(3)
                output.writeByte(if (incremental) 1 else 0)
                output.writeShort(0); output.writeShort(0)
                output.writeShort(framebufferWidth)
                output.writeShort(framebufferHeight)
                output.flush()
                incremental = true

                val msgType = input.readUnsignedByte()
                when (msgType) {
                    0 -> { // FramebufferUpdate
                        input.readUnsignedByte() // padding
                        val rects = input.readUnsignedShort()
                        for (r in 0 until rects) {
                            val rx = input.readUnsignedShort()
                            val ry = input.readUnsignedShort()
                            val rw = input.readUnsignedShort()
                            val rh = input.readUnsignedShort()
                            val encoding = input.readInt()
                            when (encoding) {
                                0 -> { // Raw
                                    val n = rw * rh
                                    val buf = ByteArray(n * 4)
                                    input.readFully(buf)
                                    for (i in 0 until n) {
                                        val px = i % rw
                                        val py = i / rw
                                        val gx = rx + px
                                        val gy = ry + py
                                        if (gx in 0 until framebufferWidth &&
                                            gy in 0 until framebufferHeight
                                        ) {
                                            val o = i * 4
                                            val b0 = buf[o].toInt() and 0xFF
                                            val b1 = buf[o + 1].toInt() and 0xFF
                                            val b2 = buf[o + 2].toInt() and 0xFF
                                            val b3 = buf[o + 3].toInt() and 0xFF
                                            // Little-endian 32bpp: byte0=blue,
                                            // byte1=green, byte2=red.
                                            val argb = (0xFF shl 24) or
                                                (b2 shl 16) or (b1 shl 8) or b0
                                            @Suppress("UNUSED_VARIABLE")
                                            val ignored = b3
                                            pixels[gy * framebufferWidth + gx] = argb
                                        }
                                    }
                                }
                                1 -> { // CopyRect
                                    val sx = input.readUnsignedShort()
                                    val sy = input.readUnsignedShort()
                                    val tmp = IntArray(rw * rh)
                                    for (py in 0 until rh) {
                                        for (px in 0 until rw) {
                                            val gx = sx + px
                                            val gy = sy + py
                                            tmp[py * rw + px] =
                                                if (gx in 0 until framebufferWidth &&
                                                    gy in 0 until framebufferHeight
                                                ) pixels[gy * framebufferWidth + gx]
                                                else 0
                                        }
                                    }
                                    for (py in 0 until rh) {
                                        for (px in 0 until rw) {
                                            val gx = rx + px
                                            val gy = ry + py
                                            if (gx in 0 until framebufferWidth &&
                                                gy in 0 until framebufferHeight
                                            ) pixels[gy * framebufferWidth + gx] =
                                                tmp[py * rw + px]
                                        }
                                    }
                                }
                                else -> {
                                    onStatus("VNC: unsupported encoding $encoding")
                                    return
                                }
                            }
                        }
                        val bmp = Bitmap.createBitmap(
                            pixels, framebufferWidth, framebufferHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        onFrame(bmp)
                    }
                    1 -> { // SetColourMapEntries (not used for true colour)
                        input.readUnsignedByte()
                        val first = input.readUnsignedShort()
                        val n = input.readUnsignedShort()
                        @Suppress("UNUSED_VARIABLE")
                        val f = first
                        input.skipBytes(n * 6)
                    }
                    2 -> { // Bell: ignore
                    }
                    3 -> { // ServerCutText
                        input.skipBytes(3)
                        val len = input.readInt()
                        input.skipBytes(len)
                    }
                    else -> {
                        onStatus("VNC: unknown message type $msgType")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) {
                onStatus("VNC: disconnected (${e.message ?: e.javaClass.simpleName})")
            }
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }
}
