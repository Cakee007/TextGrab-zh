package dev.noah.textgrab

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
 * ## Lifetime
 *
 * The activity does not linger. Creating it is enough to win the focus fight,
 * so it hands over to [CaptureAccessibilityService] and finishes immediately.
 * The service then captures the moment the shade leaves the window list —
 * before this app's OCR screen exists, and after the panel is gone. That is
 * what keeps both the control centre and TextGrab itself out of the picture.
 */
class CaptureProxyActivity : Activity() {

    companion object {
        private const val TAG = "TextGrabCapture"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Invisible window: the point is only to take focus away from the shade.
        val root = View(this)
        root.setBackgroundColor(0x00000000)
        setContentView(root)

        Log.d(TAG, "proxy activity shown")

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

        // Hand over, then get out of the way so the app underneath is visible
        // again when the capture happens.
        service.armForShadeDismissal()
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
}
