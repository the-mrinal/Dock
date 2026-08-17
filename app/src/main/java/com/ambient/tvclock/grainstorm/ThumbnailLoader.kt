package com.ambient.tvclock.grainstorm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.ambient.tvclock.HttpClients
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Loads wallpaper thumbnails into a grid.
 *
 * The same shape as [com.ambient.tvclock.AlbumArtLoader] — a small LRU plus a
 * background executor — rather than pulling in Glide or Coil. The dock ships
 * no image library and CONTRIBUTING is explicit about not adding dependencies
 * for things the app already does.
 *
 * Cheap by construction: the server's `thumb` rendition is ~400px, so the
 * whole visible grid costs a fraction of the single 14 MB wallpaper bitmap.
 */
class ThumbnailLoader(private val authHeader: String?) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "wallpaper-thumb").apply { isDaemon = true }
    }

    /**
     * Bind [url] into [view]. The url is tagged on the view so a recycled cell
     * whose request lands late does not paint the wrong wallpaper.
     */
    fun load(url: String, view: ImageView) {
        view.setTag(TAG_KEY, url)
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        io.execute {
            val bitmap = fetch(url)
            main.post {
                if (view.getTag(TAG_KEY) != url) return@post
                if (bitmap != null) view.setImageBitmap(bitmap)
            }
        }
    }

    fun shutdown() {
        io.shutdownNow()
    }

    private fun fetch(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return try {
            val builder = Request.Builder().url(url).get()
            if (!authHeader.isNullOrBlank()) builder.header("Authorization", authHeader)
            HttpClients.shared.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(url, it) }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val TAG_KEY = R_ID

        /** 6 MB of thumbnails is a couple of screens' worth and still an order
         *  of magnitude under the wallpaper bitmap the dock already carries. */
        private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
}

// A view tag needs a unique key resource; reusing an existing id is safe here
// because nothing else tags these cells.
private val R_ID = com.ambient.tvclock.R.id.wallpaperThumb
