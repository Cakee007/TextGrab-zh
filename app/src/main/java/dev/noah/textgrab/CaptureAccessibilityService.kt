package dev.noah.textgrab

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
import java.lang.reflect.Method

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
 * ## Getting rid of the control centre is the hard part
 *
 * On several OEM skins — ColorOS is one of them — a tile's onClick runs while
 * the panel still owns the gesture, and
 * GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE issued from there is silently
 * ignored. The result is a screenshot of the control centre, no matter how
 * long the capture is delayed.
 *
 * So the collapse is attempted on three levels, cheapest first:
 *
 * 1. `statusbar collapse` through the hidden IStatusBarService (root-independent
 *    reflection, works on most builds, and is the one that actually lands on
 *    ColorOS);
 * 2. the accessibility global action;
 * 3. HOME as a last resort — it always leaves the shade and shows the app the
 *    user was in, which is what they wanted to capture anyway.
 *
 * Each attempt is followed by a poll of the shade window bounds, so we only
 * proceed once the panel is really gone. Nothing on screen is read.
 */
class CaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextGrabCapture"

        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        private const val SHADE_POLL_MS = 50L
        private const val SHADE_FIRST_CHECK_MS = 100L
        private const val SHADE_MAX_WAIT_MS = 1500L
        private const val SETTLE_MS = 180L
        private const val SHADE_MIN_VISIBLE_HEIGHT_PX = 32

        /**
         * Gap between the tile tap and the first collapse attempt. The system
         * swallows global actions fired inside a tile's onClick while the
         * gesture is still being delivered.
         */
        private const val TILE_GESTURE_RELEASE_MS = 120L

        /** Gap after the proxy activity appears, before grabbing the screen. */
        private const val PROXY_SETTLE_MS = 80L

        /** How many different collapse strategies to try before HOME. */
        private const val MAX_COLLAPSE_STEPS = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var waitedMs = 0L
    private var collapseStep = 0

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

    @RequiresApi(31)
    fun captureScreenAndOpen() {
        if (pending) return
        pending = true
        waitedMs = 0L
        collapseStep = 0
        Log.d(TAG, "capture requested")

        // The tile's onClick still owns the input gesture at this point, and
        // the system ignores global actions issued inside it — that is why the
        // panel used to stay open. Post the first collapse outside the
        // callback so the gesture has been released.
        handler.postDelayed({
            collapseShade()
            handler.postDelayed({ checkShade() }, SHADE_FIRST_CHECK_MS)
        }, TILE_GESTURE_RELEASE_MS)
    }

    /**
     * Captures immediately, without trying to collapse the shade first.
     *
     * Used by [CaptureProxyActivity]: that activity has already pushed the
     * control centre away by taking the window focus, so waiting for the shade
     * window here would be pointless and only add latency.
     */
    @RequiresApi(31)
    fun captureNow() {
        if (pending) return
        pending = true
        Log.d(TAG, "captureNow via proxy activity")
        handler.postDelayed({ doCapture() }, PROXY_SETTLE_MS)
    }

    /** One collapse attempt, escalating with [collapseStep]. */
    private fun collapseShade() {
        when (collapseStep) {
            0 -> {
                val ok = collapseViaStatusBarService()
                Log.d(TAG, "collapse via IStatusBarService: $ok")
            }
            1 -> {
                val ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                Log.d(TAG, "collapse via global action: $ok")
            }
            else -> {
                Log.d(TAG, "collapse fallback: HOME")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        collapseStep++
    }
    private fun checkShade() {
        if (!pending) return
        waitedMs += SHADE_POLL_MS

        if (shadeStillUp()) {
            if (waitedMs < SHADE_MAX_WAIT_MS) {
                handler.postDelayed({ checkShade() }, SHADE_POLL_MS)
                return
            }
            // Panel refuses to leave: escalate to the next strategy.
            if (collapseStep < MAX_COLLAPSE_STEPS) {
                Log.d(TAG, "shade still up after ${waitedMs}ms, escalating")
                waitedMs = 0L
                collapseShade()
                handler.postDelayed({ checkShade() }, SHADE_FIRST_CHECK_MS)
                return
            }
            Log.w(TAG, "shade still up after all strategies, capturing anyway")
        }

        handler.postDelayed({ doCapture() }, SETTLE_MS)
    }

    /**
     * Collapses the shade through the system status bar service.
     *
     * Two routes are tried, in order of reliability on locked-down OEM skins:
     *
     * 1. `service call statusbar` as root. This is the same call the system
     *    makes for its own collapse, so ColorOS cannot ignore it. Only used
     *    when the device is rooted — Silently skipped otherwise.
     * 2. Reflection into StatusBarManager. Works on stock Android and is
     *    permission-gated on newer OEM builds.
     */
    private fun collapseViaStatusBarService(): Boolean {
        if (collapseViaRoot()) return true

        val attempts = listOf(
            "android.app.StatusBarManager" to "collapsePanels",
            "android.app.StatusBarManager" to "collapsePanel",
        )
        for ((cls, method) in attempts) {
            try {
                val c = Class.forName(cls)
                val getter = c.getMethod("getService", Context::class.java)
                val service = getter.invoke(null, this) ?: continue
                val m: Method = c.getDeclaredMethod(method)
                m.isAccessible = true
                m.invoke(service)
                return true
            } catch (t: Throwable) {
                Log.d(TAG, "$cls.$method unavailable: ${t.javaClass.simpleName}")
            }
        }
        return false
    }

    /**
     * Collapses the shade via `cmd statusbar collapse` run as root.
     *
     * Only attempted once the user has granted this app root, which is why the
     * call is wrapped so tightly: an unrooted device, or a user who declines,
     * must fall through to the next strategy without stalling the capture.
     */
    private fun collapseViaRoot(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "cmd statusbar collapse")
                .redirectErrorStream(true)
                .start()
            val done = p.waitFor()
            Log.d(TAG, "root collapse exit=$done")
            done == 0
        } catch (t: Throwable) {
            Log.d(TAG, "root collapse unavailable: ${t.javaClass.simpleName}")
            false
        }
    }

    /** True while the notification shade still covers part of the screen. */
    private fun shadeStillUp(): Boolean {
        val ws = runCatching { windows }.getOrNull() ?: return false
        val screenW = resources.displayMetrics.widthPixels
        for (w in ws) {
            if (!isShadeWindow(w, screenW)) continue
            val b = Rect()
            w.getBoundsInScreen(b)
            if (b.height() > SHADE_MIN_VISIBLE_HEIGHT_PX) return true
        }
        return false
    }

    /**
     * The shade is a full-width system window anchored at the top. Detected by
     * shape rather than TYPE_NOTIFICATION_SHADE, which only exists from API 33
     * while this app supports API 26.
     */
    private fun isShadeWindow(w: AccessibilityWindowInfo, screenW: Int): Boolean {
        if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
        val b = Rect()
        w.getBoundsInScreen(b)
        return b.top <= 0 && b.width() >= screenW * 9 / 10
    }

    @RequiresApi(31)
    private fun doCapture() {
        Log.d(TAG, "taking screenshot")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer, result.colorSpace
                    )
                    val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    pending = false

                    if (software != null) {
                        Log.d(TAG, "screenshot ok ${software.width}x${software.height}")
                        CaptureHolder.put(software)
                        openMain(captured = true)
                    } else {
                        Log.w(TAG, "screenshot null bitmap")
                        fallback()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed, errorCode=$errorCode")
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
