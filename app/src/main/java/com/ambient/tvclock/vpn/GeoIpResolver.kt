package com.ambient.tvclock.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Resolves the current public-egress country code by hitting CloudFlare's
 * `cdn-cgi/trace` endpoint. Because the request travels through the active
 * VPN tunnel, the reported `loc=XX` is whatever CloudFlare sees coming out
 * of the peer endpoint — i.e. exactly the geo we want to surface.
 *
 * Returns null on any failure (timeout, no `loc=` line, etc.) so callers can
 * fall back to a generic "VPN" label.
 *
 * Result is cached for [CACHE_TTL_MS] to avoid hammering CloudFlare every
 * time the bus republishes the same Up state.
 */
object GeoIpResolver {

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val CACHE_TTL_MS = 5L * 60 * 1000

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var cachedCountry: String? = null
    @Volatile private var cachedAt: Long = 0L

    suspend fun currentCountry(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedCountry?.let { if (now - cachedAt < CACHE_TTL_MS) return@withContext it }
        val fetched = fetch()
        if (fetched != null) {
            cachedCountry = fetched
            cachedAt = now
        }
        fetched
    }

    fun invalidate() {
        cachedCountry = null
        cachedAt = 0
    }

    private fun fetch(): String? = try {
        val req = Request.Builder().url(TRACE_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            body.lineSequence()
                .firstOrNull { it.startsWith("loc=") }
                ?.removePrefix("loc=")
                ?.trim()
                ?.takeIf { it.length == 2 }
        }
    } catch (e: Exception) {
        Timber.d(e, "GeoIpResolver: trace fetch failed")
        null
    }
}
