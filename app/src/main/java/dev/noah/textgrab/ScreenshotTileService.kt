package dev.noah.textgrab

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick-settings tile. Preferred path: the accessibility service captures
 * the current screen itself, so one tile tap goes straight to selectable
 * text. Fallback (service not enabled): open the newest screenshot.
 */
class ScreenshotTileService : TileService() {

    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 31 && CaptureAccessibilityService.instance != null) {
            // Launch a transparent activity instead of collapsing the shade
            // directly: on OEM skins the tile cannot dismiss the panel, but a
            // window that takes focus always pushes it away. That activity then
            // asks the service to capture the unobstructed screen.
            val intent = Intent(this, CaptureProxyActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
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
            return
        }

        // Fallback: no accessibility service, or pre-Android-12.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(MainActivity.EXTRA_LATEST_SCREENSHOT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
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
