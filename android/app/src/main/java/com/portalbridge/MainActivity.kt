package com.portalbridge

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Single foreground process that does both jobs at once:
 *  - serves the front camera as MJPEG on :8080 (Mac pulls it via `adb forward`)
 *  - pulls the Mac's virtual screen MJPEG from localhost:8081 (via `adb reverse`) and
 *    paints it to a fullscreen SurfaceView.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private var cameraServer: CameraMjpegServer? = null
    private var screenClient: ScreenMjpegClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surface)
        hideSystemUi()

        screenClient = ScreenMjpegClient("http://localhost:8081", surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                screenClient?.start()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                screenClient?.stop()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            // Normally granted via `adb shell pm grant`; request as a fallback.
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun startCamera() {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        // 1280x720 is the ceiling: the raw 12MP sensor (camera 1, 1080p-capable) is hidden from
        // third-party apps — cameraIdList only exposes Meta's processed camera 0.
        // For experiments: adb shell am start -n com.portalbridge/.MainActivity --es cameraId 1
        cameraServer = CameraMjpegServer(
            mgr, port = 8080, forcedCameraId = intent.getStringExtra("cameraId")
        ).also { it.start() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraServer?.stop()
        screenClient?.stop()
    }
}
