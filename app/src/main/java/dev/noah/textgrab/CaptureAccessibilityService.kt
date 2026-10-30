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
 * ## How a tile tap turns into text
 *
 * 1. The tile starts [CaptureProxyActivity]. That activity is what actually
 *    dismisses the control centre; see its documentation for why nothing
 *    else works on this OEM.
 * 2. Once the panel is gone the activity asks this service for a capture.
 * 3. The capture targets a single window rather than the whole display, so
 *    the helper, the shade and anything else floating around stay out of the
 *    picture.
 * 4. The bitmap goes straight to [MainActivity] in memory.
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextGrabCapture"

        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /** Gap before a capture that is not preceded by a helper activity. */
        private const val DIRECT_SETTLE_MS = 120L

        /** Give up waiting for the shade to close after this long. */
        private const val ARM_TIMEOUT_MS = 1200L

        /**
         * `AccessibilityWindowInfo.TYPE_NOTIFICATION`, spelled out because the
         * named constant only exists from API 33 and this app supports 26.
         */
        private const val WINDOW_TYPE_NOTIFICATION = 5
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    /** Waiting for the shade to leave the window list. */
    private var armed = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        pending = false
        armed = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !armed) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return
        }

        // The shade has left the window list: this is the first instant the
        // app underneath is visible again, and the last one before anything of
        // ours could cover it. Capture right here.
        if (!shadeVisible() && !helperOnScreen()) {
            armed = false
            Log.d(TAG, "shade gone, capturing event=${event.eventType}")
            grab()
        }
    }

    /** True while the notification shade is part of the window list. */
    private fun shadeVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        return ws.any { it.type == WINDOW_TYPE_NOTIFICATION }
    }

    /** True while the transparent helper activity is still on screen. */
    private fun helperOnScreen(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        return ws.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && isOwnWindow(it) }
    }

    override fun onInterrupt() = Unit

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
     * Starts the chain that ends with a capture of the unobstructed app.
     *
     * [CaptureProxyActivity] calls this as soon as it is created, i.e. while
     * the control centre is still on screen. This service then waits for the
     * window list to stop containing the shade — immediately before it would
     * slide back — and only then grabs the screen. Capturing on that event is
     * what keeps the result free of our own UI: the OCR screen is started only
     * after the bitmap is in memory.
     */
    @RequiresApi(31)
    fun armForShadeDismissal() {
        if (pending) return
        pending = true
        armed = true
        Log.d(TAG, "armed, waiting for shade to close")
        handler.postDelayed({ onArmTimeout() }, ARM_TIMEOUT_MS)
    }

    /** Fallback for skins that do not report the shade as a window. */
    @RequiresApi(31)
    private fun onArmTimeout() {
        if (!armed) return
        armed = false
        Log.w(TAG, "shade never reported closed, capturing anyway")
        grab()
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
        Log.d(TAG, "capturing full display looper=main")
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
            Log.d(TAG, "captured ${software.width}x${software.height}")
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
     * system overlays are excluded by construction. Our own helper activity is
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
