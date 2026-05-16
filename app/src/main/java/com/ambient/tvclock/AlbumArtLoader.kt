package com.ambient.tvclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors

object AlbumArtLoader {

    private val http = OkHttpClient()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((4 * 1024 * 1024).coerceAtLeast(1)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String?, imageView: ImageView, placeholderRes: Int = R.drawable.ic_music_placeholder) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            imageView.tag = null
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.tag = url
            return
        }
        imageView.setImageResource(placeholderRes)
        imageView.tag = url
        executor.execute {
            val bitmap = fetchBitmap(url) ?: return@execute
            cache.put(url, bitmap)
            mainHandler.post {
                if (imageView.tag == url) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun clear(imageView: ImageView, placeholderRes: Int = R.drawable.ic_music_placeholder) {
        imageView.tag = null
        imageView.setImageResource(placeholderRes)
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val request = Request.Builder().url(url).get().build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }
}
