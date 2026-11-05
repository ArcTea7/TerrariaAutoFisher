package com.example.terrariaautofisher

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Only hosts Android's screen-sharing consent dialog; returns to the game afterwards. */
class CapturePermissionActivity : ComponentActivity() {
    companion object {
        const val AREA = "watch_area"
        const val WIDTH = "screen_width"
        const val HEIGHT = "screen_height"
        const val REQUEST = "capture_request"
        const val DATA = "projection_data"
        const val RESULT = "projection_result"
    }

    private var handled = false
    private val requestId: Long get() = intent.getLongExtra(REQUEST, -1L)
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handled = true
        val overlay = FishingAccessibilityService.instance
        if (overlay?.acceptsCaptureRequest(requestId) == true) {
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                try {
                    startForegroundService(Intent(this, ScreenCaptureService::class.java)
                        .putExtras(intent)
                        .putExtra(DATA, data)
                        .putExtra(RESULT, result.resultCode))
                } catch (error: RuntimeException) {
                    overlay.captureRequestFailed(requestId, "Could not start screen capture. Try Play again.")
                }
            } else {
                overlay.captureRequestFailed(requestId, "Screen access cancelled. Tap Start to retry.")
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FishingAccessibilityService.instance?.acceptsCaptureRequest(requestId) != true) {
            handled = true
            finish()
            return
        }
        if (savedInstanceState == null) {
            try {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    manager.createScreenCaptureIntent()
                }
                consent.launch(captureIntent)
            } catch (error: RuntimeException) {
                handled = true
                FishingAccessibilityService.instance?.captureRequestFailed(requestId,
                    "Screen capture could not open. Try Play again.")
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && !handled) {
            FishingAccessibilityService.instance?.captureRequestFailed(requestId, "Screen access cancelled.")
        }
        super.onDestroy()
    }
}
