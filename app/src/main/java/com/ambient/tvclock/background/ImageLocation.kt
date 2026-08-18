package com.ambient.tvclock.background

import java.io.File
import java.net.URI

/**
 * Where the bytes behind an image URI actually come from.
 *
 * This is its own type because getting it wrong is *silent*. A cached
 * wallpaper reaches the surface as `File.toURI().toString()`, which yields
 * `file:/data/...` with a single slash — not the `file://` form a reader
 * naturally checks for. Miss that and the URI falls through to the network
 * branch, where an HTTP client rejects the `file:` scheme; the exception is
 * swallowed by the loader's catch-all and the wallpaper simply never paints,
 * with no error anywhere to say so. That was a real bug.
 *
 * Naming the decision, and pinning it from both ends in tests, is what stops
 * the producer of these URIs and the reader of them from drifting apart
 * again.
 */
sealed interface ImageLocation {

    /** Bytes to read from this device's disk. */
    data class OnDisk(val file: File) : ImageLocation

    /** Bytes to fetch over the network. */
    data class Url(val value: String) : ImageLocation

    companion object {

        /**
         * Resolve [uri] to somewhere bytes can be read from.
         *
         * Null means nothing can be done with it — including a `file:` URI
         * that will not parse. That case must never degrade into [Url]: a
         * local path retried as a network request is exactly the failure this
         * type exists to prevent.
         */
        fun of(uri: String): ImageLocation? {
            if (uri.isBlank()) return null
            // A bare absolute path — no scheme to interpret.
            if (uri.startsWith("/")) return OnDisk(File(uri))
            // Every spelling of a file URI: `file:/x`, `file:///x`.
            if (uri.startsWith("file:")) {
                return try {
                    OnDisk(File(URI.create(uri)))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            return Url(uri)
        }
    }
}
