package dev.noah.textgrab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Debug-only entry point that runs exactly what a quick-settings tile tap does.
 *
 * Meant for scripted verification over adb/root, where simulating a tap on the
 * shade tile is unreliable:
 *
 * ```
 * adb shell am broadcast -a dev.noah.textgrab.zh.DEBUG_CAPTURE \
 *     -p dev.noah.textgrab.zh --receiver-include-background
 * ```
 *
 * It is deliberately not exported to other apps: the receiver is declared
 * `exported="false"` and additionally requires [PERMISSION], so a third-party
 * app cannot trigger screenshots silently.
 */
class DebugCaptureReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURE = "dev.noah.textgrab.zh.DEBUG_CAPTURE"
        const val PERMISSION = "dev.noah.textgrab.zh.permission.DEBUG_CAPTURE"
        private const val TAG = "TextGrabCapture"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAPTURE) return
        Log.d(TAG, "debug capture broadcast")
        if (Build.VERSION.SDK_INT < 31) return

        val service = CaptureAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "debug capture: accessibility service not connected")
            return
        }
        service.captureScreenAndOpen()
    }
}
