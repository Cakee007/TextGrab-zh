package dev.noah.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Hands a freshly captured screen bitmap from the accessibility service
 * to MainActivity without going through disk.
 */
object CaptureHolder {
    private var bitmap: Bitmap? = null

    @Synchronized
    fun put(b: Bitmap) {
        bitmap = b
    }

    @Synchronized
    fun take(): Bitmap? {
        val b = bitmap
        bitmap = null
        return b
    }
}

/**
 * Screenshot-only accessibility service. Lets the quick-settings tile capture
 * the current screen by itself, so "tile tap -> selectable text" needs no
 * manual screenshot first.
 *
 * ## Shade handling
 *
 * The tile is tapped while the notification shade is open, and the shade is a
 * full-screen window of its own. If the capture fires before the shade has
 * actually collapsed, the screenshot is of the control panel instead of the
 * app the user wanted — exactly the bug this class now avoids.
 *
 * Sequence used for every capture:
 *
 * 1. send [GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE] immediately;
 * 2. poll the window list until the shade window is gone from the screen,
 *    i.e. its bounds collapsed;
 * 3. wait a short settle gap so the last animation frames are not caught;
 * 4. take the screenshot.
 *
 * Step 2 is bounded by [SHADE_MAX_WAIT_MS] so a panel that reports oddly on
 * some OEM skin can never block the capture forever.
 *
 * The service inspects no window content — it only asks which windows exist
 * and how large their bounds are.
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /** How often the shade state is re-checked while collapsing. */
        private const val SHADE_POLL_MS = 50L

        /** First check is delayed so the collapse has visibly started. */
        private const val SHADE_FIRST_CHECK_MS = 120L

        /** Upper bound on waiting for the shade to go away. */
        private const val SHADE_MAX_WAIT_MS = 2500L

        /**
         * Gap between "shade is gone" and the actual capture. Keeps the shot
         * from catching the final frames of the collapse animation while
         * staying short enough that the tile still feels immediate.
         */
        private const val SETTLE_MS = 200L

        /**
         * A collapsed shade still exists as a window but reports a height at
         * or below this many pixels (all OEMs seen so far report 0). Anything
         * taller means the panel is still occupying the screen.
         */
        private const val SHADE_MIN_VISIBLE_HEIGHT_PX = 32
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var waitedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        pending = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Collapses the control centre, confirms it is gone, then captures and
     * opens the viewer. Ignored while a previous capture is still settling.
     */
    @RequiresApi(31)
    fun captureScreenAndOpen() {
        if (pending) return
        pending = true
        waitedMs = 0L

        // Step 1: start collapsing right away — this is what makes the tile
        // feel responsive; the wait below happens off the interaction path.
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)

        // Step 2 begins after the panel has had a moment to start moving.
        handler.postDelayed({ checkShade() }, SHADE_FIRST_CHECK_MS)
    }

    private fun checkShade() {
        if (!pending) return
        waitedMs += SHADE_POLL_MS

        val panelStillUp = shadeStillUp()
        if (panelStillUp && waitedMs < SHADE_MAX_WAIT_MS) {
            handler.postDelayed({ checkShade() }, SHADE_POLL_MS)
            return
        }

        // Step 3 + 4: brief settle, then capture.
        handler.postDelayed({ doCapture() }, SETTLE_MS)
    }

    /**
     * True while the notification-shade window still covers part of the
     * screen. A collapsed shade keeps existing in the window list but its
     * bounds shrink to nothing, so bounds are what decides.
     */
    private fun shadeStillUp(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_NOTIFICATION_SHADE) continue
            val b = Rect()
            w.getBoundsInScreen(b)
            if (b.height() > SHADE_MIN_VISIBLE_HEIGHT_PX) return true
        }
        return false
    }

    @RequiresApi(31)
    private fun doCapture() {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer, result.colorSpace
                    )
                    // ML Kit and our overlay need CPU-accessible pixels.
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    pending = false

                    if (software != null) {
                        CaptureHolder.put(software)
                        openMain(captured = true)
                    } else {
                        fallback()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    fallback()
                }
            }
        )
    }

    private fun fallback() {
        pending = false
        Toast.makeText(this, R.string.capture_failed, Toast.LENGTH_SHORT).show()
        openMain(captured = false)
    }

    private fun openMain(captured: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(
                if (captured) MainActivity.EXTRA_CAPTURED_SCREEN
                else MainActivity.EXTRA_LATEST_SCREENSHOT,
                true
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}
