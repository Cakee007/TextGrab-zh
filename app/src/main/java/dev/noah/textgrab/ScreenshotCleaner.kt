package dev.noah.textgrab

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes the screenshots TextGrab has looked at, so nothing is left behind
 * once the user leaves the app.
 *
 * ## What can actually be deleted
 *
 * A capture taken by [CaptureAccessibilityService] lives only in memory and is
 * never written to the gallery, so there is nothing on disk to remove for those
 * — [CaptureHolder] simply drops the bitmap in [release].
 *
 * The screenshots this class handles are the ones the *system* saved: the file
 * behind "latest screenshot", or an image the user shared in from another app.
 * Those are real gallery entries, and they are removed through MediaStore when
 * the user leaves.
 *
 * ## Recycle bin
 *
 * On Android 11+ MediaStore deletes into the trash when the volume supports it:
 * the entry disappears from the gallery and the file is kept for 30 days in
 * the recycle bin, which is exactly what was asked for. Where the trash is not
 * available the delete is permanent; [requestDeleteForever] is deliberately
 * left false so the platform decides.
 */
object ScreenshotCleaner {

    private const val TAG = "TextGrabCleaner"

    /** Images that should be removed when the app is left. */
    private val pending = LinkedHashSet<Uri>()

    /**
     * Remembers a gallery image for deletion on exit.
     *
     * Only MediaStore images are tracked: a share from another app can hand us
     * a content URI that belongs to that app, and deleting it there is not our
     * business.
     */
    @Synchronized
    fun track(context: Context, uri: Uri) {
        if (!isGalleryImage(context, uri)) return
        pending.add(uri)
        Log.d(TAG, "tracked for deletion: $uri")
    }

    /**
     * Drops every tracked image and frees the in-memory capture.
     *
     * Safe to call from `onDestroy`; it never throws.
     */
    suspend fun releaseAll(context: Context) {
        // In-memory capture: no file exists, drop the reference so the pixels
        // can be collected.
        CaptureHolder.clear()

        val uris = synchronized(this) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (uris.isEmpty()) return

        withContext(Dispatchers.IO) {
            for (uri in uris) {
                val removed = runCatching { context.contentResolver.delete(uri, null, null) }
                    .getOrElse {
                        Log.w(TAG, "delete failed for $uri", it)
                        -1
                    }
                Log.d(TAG, "delete $uri -> $removed row(s)")
            }
        }
    }

    /**
     * True when [uri] is an entry we are allowed to remove: a MediaStore image
     * that this app can see. Anything else (another app's private provider, a
     * picked document) is left alone.
     */
    private fun isGalleryImage(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return false
        val isMediaStore = authority == MediaStore.AUTHORITY ||
            authority == "media"
        if (!isMediaStore) return false

        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return false
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val query = collection.buildUpon().appendPath(id.toString()).build()
        return runCatching {
            context.contentResolver.query(
                query,
                arrayOf(MediaStore.Images.Media._ID),
                null,
                null,
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }
}
