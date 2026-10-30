package dev.noah.textgrab

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick-settings tile.
 *
 * A tap starts [CaptureProxyActivity] rather than capturing on the spot: the
 * control centre cannot be dismissed from inside a tile callback on this OEM,
 * and only a window that takes focus pushes it away. The activity then asks
 * the accessibility service for a window-targeted capture.
 *
 * Without the accessibility service the tile degrades to opening the newest
 * screenshot, which is what upstream TextGrab always did.
 */
class ScreenshotTileService : TileService() {

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 31 && CaptureAccessibilityService.instance != null) {
            val intent = Intent(this, CaptureProxyActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivityAndCollapseCompat(intent)
            return
        }

        // Fallback: no accessibility service, or pre-Android-12.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapseCompat(intent)
    }

    /**
     * Android 14 changed [TileService.startActivityAndCollapse] to take a
     * [PendingIntent]. The older overload is deprecated but still the only one
     * that works below API 34.
     */
    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
