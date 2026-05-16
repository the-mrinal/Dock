package com.ambient.tvclock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import java.util.concurrent.Executors

object AlbumArtLoader {

    private const val MAX_DECODE_DIMENSION = 512

    private val http = HttpClients.shared
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String?, imageView: ImageView, placeholderRes: Int = R.drawable.ic_music_placeholder) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholderRes)
            imageView.tag = null
            return
        }
        if (imageView.tag == url && imageView.drawable != null) {
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
                decodeDownsampled(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIMENSION)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}
