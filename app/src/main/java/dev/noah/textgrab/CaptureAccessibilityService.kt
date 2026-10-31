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

    /** Drops the bitmap so its pixels can be freed. */
    @Synchronized
    fun clear() {
        bitmap = null
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
 * 2. **Wait until the picture stops changing.** [waitForStableScreen] keeps
 *    screenshotting and compares consecutive frames. The panel closing is an
 *    animation, so the frames differ while it runs and match once it has
 *    finished. This is the one signal that does not depend on how a given OEM
 *    reports window state, which is where earlier attempts went wrong.
 * 3. **Close the helper window**, so nothing of ours is on screen.
 * 4. **Keep the settled frame** — it was captured after the panel was gone.
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

        /** Gap between stability probes while the panel collapses. */
        private const val PROBE_INTERVAL_MS = 40L

        /** Give up waiting for a stable picture after this long. */
        private const val STABLE_DEADLINE_MS = 2000L

        /** Pixels compared across a frame, per axis. */
        private const val GRID = 48

        /**
         * Fraction of sampled pixels allowed to differ between two frames that
         * still count as "the same picture". Small enough to catch motion,
         * large enough to ignore a status-bar clock tick.
         */
        private const val STABLE_TOLERANCE = 0.01f

        /** Time given to the helper window to disappear after being closed. */
        private const val HELPER_GONE_MS = 30L

        /** Sent to [MainActivity] to make it close itself before a capture. */
        const val ACTION_DISMISS = "dev.noah.textgrab.zh.action.DISMISS"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The binding only exists while the process is alive, and OEM freezers
        // drop it once the app goes idle. A foreground service keeps the
        // process exempt from that, so the user does not have to re-enable the
        // permission after every long pause.
        KeepAliveService.start(this)
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
        Log.d(TAG, "1/4 proxy shown, waiting for the screen to settle")
        probe = null

        // Any OCR screen left over from a previous run is still in front after
        // the user came back from it. Dismiss it by asking the app to clear its
        // own task, so the only application window left is the app the user was
        // reading. Without this the second capture in a row photographs
        // TextGrab itself.
        dismissOwnScreens()

        waitForStableScreen(started) { stable ->
            Log.d(TAG, "2/4 screen settled after ${System.currentTimeMillis() - started} ms")
            closeHelper()

            handler.postDelayed({
                Log.d(TAG, "3/4 delivering the settled frame")
                pending = false
                CaptureHolder.put(stable)
                openMain(captured = true)
            }, HELPER_GONE_MS)
        }
    }

    /**
     * Closes every TextGrab screen that is not the helper.
     *
     * [MainActivity] finishes itself in [MainActivity.onNewIntent] when this
     * flag is set, which removes the previous OCR screen from the back stack
     * without launching anything new.
     */
    private fun dismissOwnScreens() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_DISMISS
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "dismiss request failed", it) }
    }

    /**
     * Waits until the on-screen picture has stopped changing, then hands over.
     *
     * Window bookkeeping is not reliable enough here: the shade stays in the
     * window list when it closes, and different OEM skins report its bounds,
     * activeness and visibility inconsistently. What is unambiguous is the
     * pixels. Collapsing the panel is an animation, so successive frames
     * differ until it has finished; once two frames in a row are identical the
     * panel is unquestionably gone and nothing is still moving.
     *
     * Each probe is a real screenshot, so the frame that finally gets kept is
     * the one that was just proven stable.
     */
    private fun waitForStableScreen(started: Long, then: (Bitmap) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) return
        if (System.currentTimeMillis() - started > STABLE_DEADLINE_MS) {
            Log.w(TAG, "screen never settled in ${STABLE_DEADLINE_MS} ms, capturing now")
            grab()
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = toSoftware(result)
                    if (bitmap == null) {
                        grab()
                        return
                    }
                    val previous = probe
                    probe = bitmap

                    if (previous != null && sameFrame(previous, bitmap)) {
                        Log.d(
                            TAG,
                            "screen stable after ${System.currentTimeMillis() - started} ms"
                        )
                        then(bitmap)
                    } else {
                        handler.postDelayed(
                            { waitForStableScreen(started, then) },
                            PROBE_INTERVAL_MS
                        )
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "probe failed ($errorCode)")
                    handler.postDelayed(
                        { waitForStableScreen(started, then) },
                        PROBE_INTERVAL_MS
                    )
                }
            }
        )
    }

    /** Last probe frame, kept to compare against the next one. */
    private var probe: Bitmap? = null

    /**
     * Cheap frame comparison: samples a coarse grid of pixels instead of
     * comparing every byte, which is enough to tell a moving animation from a
     * settled screen and costs next to nothing.
     */
    private fun sameFrame(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val stepX = (a.width / GRID).coerceAtLeast(1)
        val stepY = (a.height / GRID).coerceAtLeast(1)
        var differing = 0
        var sampled = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                sampled++
                if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
                x += stepX
            }
            y += stepY
        }
        val ratio = differing.toFloat() / sampled.coerceAtLeast(1)
        return ratio < STABLE_TOLERANCE
    }

    @RequiresApi(31)
    private fun toSoftware(result: ScreenshotResult): Bitmap? {
        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
        result.hardwareBuffer.close()
        return software
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
