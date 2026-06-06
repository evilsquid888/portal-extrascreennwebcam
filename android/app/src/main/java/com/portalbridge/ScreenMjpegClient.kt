package com.portalbridge

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceView
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connects to the Mac's MJPEG screen stream (multipart/x-mixed-replace with per-part
 * Content-Length), decodes each JPEG, and paints it to the SurfaceView. Reconnects on its
 * own so it can be started before the Mac server is up.
 */
class ScreenMjpegClient(
    private val url: String,
    private val surfaceView: SurfaceView
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "portal-screen").also { it.start() }
    }

    private fun loop() {
        while (running) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                    connect()
                }
                BufferedInputStream(conn.inputStream, 1 shl 16).use { readStream(it) }
            } catch (e: Exception) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun readStream(input: InputStream) {
        while (running) {
            val headers = readHeaders(input) ?: return
            val len = headers["content-length"]?.toIntOrNull() ?: return
            val data = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = input.read(data, read, len - read)
                if (r < 0) return
                read += r
            }
            input.read(); input.read() // trailing CRLF after the JPEG
            drawFrame(data)
        }
    }

    /** Reads lines until a blank line terminates this part's headers. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) {
                if (headers.isNotEmpty()) return headers
                // leading blank line before the boundary — keep going
            } else if (line.startsWith("--")) {
                // boundary marker — ignore
            } else {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private fun drawFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: run { bmp.recycle(); return }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            val dst = Rect(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bmp, null, dst, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
            bmp.recycle()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    companion object {
        private const val TAG = "ScreenMjpegClient"
    }
}
