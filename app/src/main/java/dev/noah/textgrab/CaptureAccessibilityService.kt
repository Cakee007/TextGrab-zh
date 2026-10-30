package dev.noah.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Screenshot-only accessibility service.
 *
 * ## The capture sequence
 *
 * The order matters and is strictly enforced, step by step:
 *
 * 1. **[CaptureProxyActivity] is created.** It takes the window focus, which
 *    is what makes the control centre start collapsing. The tile itself cannot
 *    do that on this OEM.
 * 2. **Wait for the panel to be fully gone.** [shadeVisible] polls the window
 *    list until the notification shade is no longer part of it, plus a short
 *    settle delay so the closing animation has finished. There is a hard
 *    deadline so a panel that never reports itself can't stall the flow.
 * 3. **Close the helper window**, so nothing of ours is on screen.
 * 4. **Capture.** A single window, picked as the largest application window
 *    that is not ours.
 * 5. **Only then** start [MainActivity] with the bitmap in hand.
 *
 * No step starts before the previous one has demonstrably finished, and the
 * OCR screen is never launched before the bitmap exists.
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextGrabCapture"

        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /**
         * `AccessibilityWindowInfo.TYPE_NOTIFICATION`, spelled out because the
         * named constant only exists from API 33 and this app supports 26.
         */
        private const val WINDOW_TYPE_NOTIFICATION = 5

        /** Logs every system window's visibility while waiting for the shade. */
        private const val SHADE_TRACE = true

        /** How often the window list is polled while waiting for the shade. */
        private const val SHADE_POLL_MS = 25L

        /** Longest time spent waiting for the shade before going ahead anyway. */
        private const val SHADE_DEADLINE_MS = 1500L

        /** Settle time after the shade is gone, to let the animation finish. */
        private const val SHADE_SETTLE_MS = 60L

        /** Time given to the helper window to disappear after being closed. */
        private const val HELPER_GONE_MS = 30L

        /** Gap before a capture that is not preceded by a helper activity. */
        private const val DIRECT_SETTLE_MS = 120L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

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
     * Runs the whole sequence described in the class documentation.
     *
     * [closeHelper] is called once the panel is fully gone and is responsible
     * for dismissing [CaptureProxyActivity]; the capture happens after it has
     * returned, not before.
     */
    @RequiresApi(31)
    fun startCaptureSequence(closeHelper: () -> Unit) {
        if (pending) return
        pending = true
        val started = System.currentTimeMillis()
        Log.d(TAG, "1/4 proxy shown, waiting for shade to close")

        waitForShadeGone(started) {
            Log.d(TAG, "2/4 shade gone after ${System.currentTimeMillis() - started} ms")
            closeHelper()

            // Give the window manager a moment to actually drop the helper
            // window before grabbing, so nothing of ours can be in frame.
            handler.postDelayed({
                Log.d(TAG, "3/4 capturing")
                grab()
            }, HELPER_GONE_MS)
        }
    }

    /**
     * Captures without a helper activity in front. Used by the scripted debug
     * entry point; the tile itself goes through [CaptureProxyActivity] first.
     */
    @RequiresApi(31)
    fun captureScreenAndOpen() {
        if (pending) return
        pending = true
        Log.d(TAG, "capture requested (direct)")
        handler.postDelayed({ grab() }, DIRECT_SETTLE_MS)
    }

    /**
     * Polls until the notification shade has left the window list.
     *
     * Polling rather than reacting to an event keeps the sequence linear: the
     * callback fires exactly once, either when the shade is gone or when the
     * deadline expires, whichever comes first.
     */
    private fun waitForShadeGone(started: Long, then: () -> Unit) {
        if (!shadeVisible()) {
            handler.postDelayed(then, SHADE_SETTLE_MS)
            return
        }
        if (System.currentTimeMillis() - started >= SHADE_DEADLINE_MS) {
            Log.w(TAG, "shade still reported after ${SHADE_DEADLINE_MS} ms, continuing")
            then()
            return
        }
        handler.postDelayed({ waitForShadeGone(started, then) }, SHADE_POLL_MS)
    }

    /**
     * True while the notification shade is on screen and visible.
     *
     * The shade window is never destroyed when it closes — it stays in the
     * window list and is merely made invisible (`mViewVisibility=0x4`, i.e.
     * `View.GONE`). Checking membership of the list alone therefore reported
     * "already collapsed" the instant it was opened, and the capture grabbed
     * the control centre. What matters is visibility:
     *
     * - [AccessibilityWindowInfo.isActive] / focus are not reliable here;
     * - the shade reports itself as `TYPE_SYSTEM` on this platform and also
     *   exposes `isVisibleToUser`, which covers both cases.
     */
    private fun shadeVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        val metrics = resources.displayMetrics
        var result = false
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                w.type != WINDOW_TYPE_NOTIFICATION
            ) {
                continue
            }
            val b = Rect()
            w.getBoundsInScreen(b)
            // The shade spans the full width and starts at the very top; the
            // bars are either thin or anchored to the bottom.
            val fullWidth = b.width() >= metrics.widthPixels * 9 / 10 && b.top <= 0
            val tall = b.height() >= metrics.heightPixels / 4
            val visible = fullWidth && tall && w.isVisibleToUser
            if (SHADE_TRACE) {
                Log.d(TAG, "system window id=${w.id} ${b.toShortString()} vis=${w.isVisibleToUser} counts=$visible")
            }
            if (visible) result = true
        }
        return result
    }

    /** Grabs the app window the user is looking at, then opens the OCR UI. */
    @RequiresApi(31)
    private fun grab() {
        val target = primaryWindow()
        Log.d(TAG, "windows: " + runCatching { windows }.getOrNull()?.joinToString(" | ") { w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
            "id=${w.id} type=${w.type} active=${w.isActive} focus=${w.isFocused} pkg=$pkg ${r.toShortString()}"
        })

        if (Build.VERSION.SDK_INT >= 34 && target != null) {
            Log.d(TAG, "capturing window id=${target.id} type=${target.type}")
            takeScreenshotOfWindow(
                target.id,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) = deliver(result)
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "window capture failed ($errorCode), trying display")
                        captureDisplay()
                    }
                }
            )
            return
        }
        captureDisplay()
    }

    @RequiresApi(31)
    private fun captureDisplay() {
        Log.d(TAG, "capturing full display")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) = deliver(result)
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "display capture failed, errorCode=$errorCode")
                    fallback()
                }
            }
        )
    }

    @RequiresApi(31)
    private fun deliver(result: ScreenshotResult) {
        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        // ML Kit and our overlay need CPU-accessible pixels.
        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
        result.hardwareBuffer.close()
        pending = false

        if (software != null) {
            Log.d(TAG, "4/4 captured ${software.width}x${software.height}, opening UI")
            CaptureHolder.put(software)
            openMain(captured = true)
        } else {
            fallback()
        }
    }

    /**
     * Picks the window worth capturing: the largest application window that is
     * not ours.
     *
     * Only TYPE_APPLICATION windows qualify, so the notification shade and
     * system overlays are excluded by construction. Our own windows are
     * filtered out by package name.
     */
    private fun primaryWindow(): AccessibilityWindowInfo? {
        val ws = runCatching { windows }.getOrNull() ?: return null
        var best: AccessibilityWindowInfo? = null
        var bestArea = 0

        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (isOwnWindow(w)) continue

            val b = Rect()
            w.getBoundsInScreen(b)
            val area = b.width() * b.height()
            if (area > bestArea) {
                bestArea = area
                best = w
            }
        }
        return best
    }

    /**
     * True for windows produced by this app, including the transparent helper.
     *
     * Window titles are not reliable across apps, so the check asks the node
     * tree for its root package. This is the only place the service touches
     * window content, and it reads a package name only — never text.
     */
    private fun isOwnWindow(w: AccessibilityWindowInfo): Boolean {
        val root = runCatching { w.root }.getOrNull() ?: return false
        return runCatching { root.packageName?.toString() == packageName }
            .getOrDefault(false)
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
