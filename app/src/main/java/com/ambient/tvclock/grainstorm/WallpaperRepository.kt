package com.ambient.tvclock.grainstorm

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * The one place that knows how to reach the wallpaper library.
 *
 * Both the picker (browsing and choosing) and the background source (polling
 * and caching) go through here rather than talking to [GrainstormClient]
 * directly, so what you pick in Settings and what the screensaver shows can
 * never drift apart.
 *
 * Every method blocks. Callers own their threading.
 */
class WallpaperRepository(
    context: Context,
    private val clientFactory: (String, String) -> GrainstormClient = ::GrainstormClient,
) {

    private val appContext = context.applicationContext

    /** Where downloaded wallpapers live. Not the cache dir: a wallpaper must
     *  survive the system reclaiming space, or the dock boots to black. */
    private val imageDir: File get() = File(appContext.filesDir, "grainstorm")

    private fun client(): GrainstormClient = clientFactory(
        GrainstormPreferences.serverUrl(appContext),
        GrainstormPreferences.deviceToken(appContext),
    )

    fun isConfigured(): Boolean = GrainstormPreferences.isConfigured(appContext)

    fun serverUrl(): String = GrainstormPreferences.serverUrl(appContext)

    fun deviceKey(): String = GrainstormPreferences.deviceKey(appContext)

    fun ping(): GrainstormClient.Result<Boolean> = client().ping()

    fun listAssets(limit: Int = 24, cursor: String? = null) = client().listAssets(limit, cursor)

    fun register(label: String, width: Int, height: Int): GrainstormClient.Result<Boolean> {
        val result = client().register(deviceKey(), label, width, height)
        // The screen's identity on the server just changed; whatever we had
        // cached belonged to the previous registration.
        if (result is GrainstormClient.Result.Ok) GrainstormPreferences.forgetCache(appContext)
        return result
    }

    fun setCurrent(assetId: String): GrainstormClient.Result<Boolean> {
        val result = client().setCurrent(deviceKey(), assetId)
        // Force the next sync to actually fetch rather than trusting the ETag
        // it held for the previous wallpaper.
        if (result is GrainstormClient.Result.Ok) {
            GrainstormPreferences.rememberCached(
                appContext,
                etag = null,
                file = GrainstormPreferences.cachedFile(appContext).orEmpty(),
                sha = GrainstormPreferences.cachedSha(appContext).orEmpty(),
            )
        }
        return result
    }

    /** Absolute URL for a path the server gave us — for thumbnail loading. */
    fun absoluteUrl(path: String): String = SyncContract.absoluteUrl(serverUrl(), path)

    /** Bearer token for image requests, or null when reads are open. */
    fun authHeader(): String? =
        GrainstormPreferences.deviceToken(appContext).ifBlank { null }?.let { "Bearer $it" }

    sealed interface Sync {
        /** A new wallpaper is on disk and ready to show. */
        data class Updated(val file: File, val current: SyncContract.Current) : Sync
        /** Nothing changed; whatever is cached is still correct. */
        object Unchanged : Sync
        /** The dock could not reach the server, or has none configured. */
        data class Failed(val failure: GrainstormClient.Failure) : Sync
    }

    /**
     * Ask the server what this screen should show and make sure we have it.
     *
     * The usual answer is [Sync.Unchanged] for the cost of one conditional
     * request — the server ETags `current` by the image hash, so a wallpaper
     * that has not moved costs a 304 and nothing else.
     */
    fun sync(): Sync {
        if (!isConfigured()) return Sync.Failed(GrainstormClient.Failure.NotConfigured)

        val etag = GrainstormPreferences.lastEtag(appContext)
        return when (val result = client().current(deviceKey(), etag)) {
            is GrainstormClient.Result.NotModified -> {
                if (cachedFile() != null) Sync.Unchanged
                // The server says nothing changed but our copy is gone (wiped
                // storage, a manual clear). Ask again without the ETag.
                else refetch()
            }
            is GrainstormClient.Result.Err -> Sync.Failed(result.failure)
            is GrainstormClient.Result.Ok -> ensureDownloaded(result.value.first, result.value.second)
        }
    }

    private fun refetch(): Sync {
        GrainstormPreferences.forgetCache(appContext)
        return when (val retry = client().current(deviceKey(), null)) {
            is GrainstormClient.Result.Ok -> ensureDownloaded(retry.value.first, retry.value.second)
            is GrainstormClient.Result.Err -> Sync.Failed(retry.failure)
            // A 304 to a request with no ETag would be a broken server.
            is GrainstormClient.Result.NotModified -> Sync.Failed(GrainstormClient.Failure.Malformed("unexpected 304"))
        }
    }

    private fun ensureDownloaded(current: SyncContract.Current, etag: String?): Sync {
        val sha = current.image.sha256
        val existing = cachedFile()
        if (existing != null && GrainstormPreferences.cachedSha(appContext) == sha) {
            // Same image under a new ETag — no need to move any bytes.
            GrainstormPreferences.rememberCached(appContext, etag, existing.absolutePath, sha)
            return Sync.Unchanged
        }

        val target = File(imageDir, "${sha.ifBlank { current.assetId }}.png")
        return when (val downloaded = client().download(current.image.url, target, sha.ifBlank { null })) {
            is GrainstormClient.Result.Ok -> {
                GrainstormPreferences.rememberCached(appContext, etag, downloaded.value.absolutePath, sha)
                pruneOtherThan(downloaded.value)
                Sync.Updated(downloaded.value, current)
            }
            is GrainstormClient.Result.Err -> Sync.Failed(downloaded.failure)
            is GrainstormClient.Result.NotModified -> Sync.Unchanged
        }
    }

    /** The wallpaper we already have on disk, if it is still there. */
    fun cachedFile(): File? =
        GrainstormPreferences.cachedFile(appContext)
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0 }

    /** One wallpaper is enough; the rest is dead weight on a 8 GB stick. */
    private fun pruneOtherThan(keep: File) {
        val files = imageDir.listFiles() ?: return
        for (file in files) {
            if (file == keep) continue
            if (!file.delete()) Timber.d("grainstorm: could not prune %s", file.name)
        }
    }
}
