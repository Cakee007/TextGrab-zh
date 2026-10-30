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
 * Tiny transparent activity shown for a moment right after the tile is tapped.
 *
 * ## Why this exists
 *
 * On ColorOS (and some other skins) the notification shade ignores both
 * `GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE` and `StatusBarManager.collapsePanels`
 * when they are issued from a quick-settings tile: the panel keeps the input
 * focus and stays on screen, so every screenshot ends up showing the control
 * centre instead of the app underneath.
 *
 * A full-screen activity, however, always wins that fight — the window manager
 * dismisses the panel because something else is now on top. This activity is
 * transparent, lives for only a few hundred milliseconds, and immediately asks
 * the accessibility service to capture once the panel is actually gone.
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
