package com.portalbridge

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens the front camera, grabs JPEG frames via an ImageReader, and serves them as an
 * MJPEG (multipart/x-mixed-replace) stream on [port]. No on-screen preview is needed —
 * the ImageReader is the only capture target, leaving the display free for the screen client.
 */
class CameraMjpegServer(
    private val cameraManager: CameraManager,
    private val port: Int,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val targetFps: Int = 20
) {
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false

    fun start() {
        running = true
        bgThread = HandlerThread("portal-cam").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
        openCamera()
        Thread({ serve() }, "portal-cam-http").start()
    }

    /**
     * The Portal's camera HAL returns the ImageReader's full capacity buffer (~35 MB) with the
     * real JPEG at the front and zero padding after it. Trim at the EOI marker (FF D9) — it
     * can't occur inside entropy-coded JPEG data (FF bytes are stuffed as FF 00), so the first
     * occurrence is the true end of the image.
     */
    private fun trimToJpegEnd(bytes: ByteArray): ByteArray {
        var i = 2
        while (i < bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD9.toByte()) {
                val end = i + 2
                return if (end < bytes.size) bytes.copyOf(end) else bytes
            }
            i++
        }
        return bytes
    }

    private fun frontCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val c = cameraManager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return cameraManager.cameraIdList.first()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    latestJpeg.set(trimToJpegEnd(bytes))
                } finally {
                    img.close()
                }
            }, bgHandler)
        }

        try {
            cameraManager.openCamera(frontCameraId(), object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    startSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    device.close()
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
        }
    }

    private fun startSession(device: CameraDevice) {
        val target = imageReader!!.surface
        @Suppress("DEPRECATION")
        device.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(target)
                    set(CaptureRequest.JPEG_QUALITY, 70.toByte())
                }
                s.setRepeatingRequest(req.build(), null, bgHandler)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "capture session configure failed")
            }
        }, bgHandler)
    }

    private fun serve() {
        try {
            serverSocket = ServerSocket(port)
            Log.i(TAG, "camera MJPEG server on :$port")
            while (running) {
                val client = try {
                    serverSocket!!.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handleClient(client) }, "portal-cam-client").start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve failed", e)
        }
    }

    private fun handleClient(client: Socket) {
        val boundary = "frame"
        val frameIntervalMs = (1000 / targetFps).toLong()
        try {
            val out = client.getOutputStream()
            out.write(
                ("HTTP/1.0 200 OK\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n\r\n")
                    .toByteArray()
            )
            while (running && !client.isClosed) {
                val jpeg = latestJpeg.get()
                if (jpeg != null) {
                    out.write(
                        ("--$boundary\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray()
                    )
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(frameIntervalMs)
            }
        } catch (e: Exception) {
            // client disconnected — normal
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        bgThread?.quitSafely()
    }

    companion object {
        private const val TAG = "CameraMjpegServer"
    }
}
