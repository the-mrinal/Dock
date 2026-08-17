package com.ambient.tvclock.grainstorm

import org.json.JSONObject
import timber.log.Timber

/**
 * The wire contract with the grainstorm sync server — parsing only, no I/O, so
 * it is testable on the JVM without a device or a network.
 *
 * See the grainstorm repo's `docs/specs/sync-protocol.md`. Two rules matter:
 * unknown fields are ignored (the server may add them at any time) and an
 * unknown *major* version is refused rather than half-understood.
 *
 * Every parse returns null on anything malformed instead of throwing. A dock
 * on a shelf must degrade to "keep showing the cached wallpaper", never crash.
 */
object SyncContract {

    const val VERSION = 1

    /** One stored resolution of a wallpaper. */
    data class Rendition(
        val name: String,
        val url: String,
        val width: Int,
        val height: Int,
        val sha256: String,
    )

    /** One wallpaper — a design at a seed — with every resolution it has. */
    data class Asset(
        val id: String,
        val seed: Int,
        val createdAt: String,
        val quote: String,
        val color: String?,
        val renditions: List<Rendition>,
    ) {
        /**
         * What to show in a grid cell: the purpose-made thumbnail, or failing
         * that the smallest thing available. A large preview beats a blank one.
         */
        fun previewRendition(): Rendition? =
            renditions.firstOrNull { it.name == "thumb" }
                ?: renditions.minByOrNull { it.width }

        fun largestRendition(): Rendition? = renditions.maxByOrNull { it.width }
    }

    data class AssetPage(
        val assets: List<Asset>,
        val nextCursor: String?,
        val total: Int,
    )

    /** What a specific screen should be showing right now. */
    data class Current(
        val deviceKey: String,
        val assetId: String,
        val seed: Int,
        val updatedAt: String,
        val image: Rendition,
    )

    fun parseCurrent(body: String?): Current? = guarded("current") {
        val root = JSONObject(body ?: return@guarded null)
        if (root.optInt("version", -1) != VERSION) return@guarded null
        val image = root.optJSONObject("image") ?: return@guarded null
        val url = image.optString("url").ifBlank { return@guarded null }
        Current(
            deviceKey = root.optString("deviceKey"),
            assetId = root.optString("assetId"),
            seed = root.optInt("seed", -1),
            updatedAt = root.optString("updatedAt"),
            image = Rendition(
                name = "current",
                url = url,
                width = image.optInt("w"),
                height = image.optInt("h"),
                sha256 = image.optString("sha256"),
            ),
        )
    }

    fun parseAssetPage(body: String?): AssetPage? = guarded("asset page") {
        val root = JSONObject(body ?: return@guarded null)
        if (root.optInt("version", -1) != VERSION) return@guarded null
        val array = root.optJSONArray("assets") ?: return@guarded null
        val assets = ArrayList<Asset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseAsset(obj)?.let(assets::add)
        }
        AssetPage(
            assets = assets,
            // JSON null and a missing key both mean "no more pages".
            nextCursor = root.optString("nextCursor").ifBlank { null }?.takeIf { it != "null" },
            total = root.optInt("total", assets.size),
        )
    }

    private fun parseAsset(obj: JSONObject): Asset? {
        val id = obj.optString("id").ifBlank { return null }
        val design = obj.optJSONObject("design")
        val renditionsObj = obj.optJSONObject("renditions") ?: JSONObject()
        val renditions = ArrayList<Rendition>(renditionsObj.length())
        for (name in renditionsObj.keys()) {
            val r = renditionsObj.optJSONObject(name) ?: continue
            val url = r.optString("url")
            if (url.isBlank()) continue
            renditions.add(
                Rendition(
                    name = name,
                    url = url,
                    width = r.optInt("w"),
                    height = r.optInt("h"),
                    sha256 = r.optString("sha256"),
                )
            )
        }
        return Asset(
            id = id,
            seed = obj.optInt("seed", -1),
            createdAt = obj.optString("createdAt"),
            quote = design?.optString("quote").orEmpty(),
            color = obj.optString("color").ifBlank { null }?.takeIf { it != "null" },
            renditions = renditions,
        )
    }

    /** Join the configured server base with a path the server handed us. */
    fun absoluteUrl(baseUrl: String, url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    /**
     * A screen enrols itself knowing only its own pixel size; the server fills
     * in the render tuning, so the TV carries no copy of the preset table.
     */
    fun registerBody(key: String, label: String, width: Int, height: Int): String =
        """{"key":${quote(key)},"label":${quote(label)},"w":$width,"h":$height}"""

    fun setCurrentBody(assetId: String): String = """{"assetId":${quote(assetId)}}"""

    private fun quote(value: String): String = JSONObject.quote(value)

    private inline fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Exception) {
        Timber.w(e, "grainstorm: could not parse %s", what)
        null
    }
}
