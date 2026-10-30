package dev.noah.textgrab

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast

/**
 * Brief transparent activity whose only job is to push the notification shade
 * away before a capture.
 *
 * ## Why this exists
 *
 * On ColorOS the control centre ignores every way of dismissing itself that a
 * quick-settings tile can use: `startActivityAndCollapse` leaves the panel on
 * screen, accessibility global actions are swallowed, and even `su -c "cmd
 * statusbar collapse"` had no visible effect when issued from the tile
 * callback. A window that takes focus, however, always wins that fight — the
 * window manager has to dismiss the panel because something else is on top.
 *
 * The activity is transparent, animation-free and lives for a few hundred
 * milliseconds: appear → let the panel finish sliding away → ask the
 * accessibility service to capture → finish.
 *
 * It deliberately does *not* try to avoid being screenshotted by hiding
 * itself: on this OEM the activity opens in a floating window, and the capture
 * targets a single window anyway (see [CaptureAccessibilityService]), so the
 * helper can never end up inside the result.
 */
class CaptureProxyActivity : Activity() {

    companion object {
        private const val TAG = "TextGrabCapture"

        /** How long to let the shade finish animating away. */
        private const val HIDE_DELAY_MS = 260L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Invisible window: the point is only to take focus away from the shade.
        val root = View(this)
        root.setBackgroundColor(0x00000000)
        setContentView(root)

        Log.d(TAG, "proxy activity shown")
        handler.postDelayed({ startCapture() }, HIDE_DELAY_MS)
    }

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < 31) {
            finishAndOpenLatest()
            return
        }
        val service = CaptureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "proxy: accessibility service not connected")
            Toast.makeText(this, R.string.capture_unavailable, Toast.LENGTH_LONG).show()
            finishAndOpenLatest()
            return
        }
        service.captureNow()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun finishAndOpenLatest() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
