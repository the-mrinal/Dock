package com.ambient.tvclock.ui

import android.graphics.Color

/**
 * Deterministic cover palette + layout picker.
 *
 * Ported one-to-one from the HTML TV design prototype's `hashStr` / `pick` /
 * `rnd` helpers. Given the same seed string the Kotlin port produces the same
 * layout, palette, and per-shape RNG values as the JavaScript prototype, so
 * Spotify tracks render identically on the TV and in the reference mocks.
 *
 * The hash is FNV-1a 32-bit. We hold the running state in a [Long] only because
 * Kotlin's [Int] is signed and FNV-1a needs unsigned arithmetic — every
 * arithmetic step is masked back to 32 bits with [BITMASK_32] to match the
 * `>>> 0` shifts in JavaScript.
 */
object CoverPalette {

    private const val FNV_OFFSET_32 = 2166136261L
    private const val FNV_PRIME_32 = 16777619L
    private const val BITMASK_32 = 0xFFFFFFFFL

    /** Ten palettes from the HTML prototype, each `[bg, fg1, fg2, ink]`. */
    val PALETTES: List<IntArray> = listOf(
        intArrayOf(parse("#1a2a3a"), parse("#e8d5a3"), parse("#c97a4a"), parse("#f4ebd9")),
        intArrayOf(parse("#2b1f1a"), parse("#d6a16d"), parse("#7a3a2a"), parse("#f1dab8")),
        intArrayOf(parse("#0e1d1a"), parse("#4ea08a"), parse("#1f4a42"), parse("#cfeadf")),
        intArrayOf(parse("#1c1626"), parse("#a48cd6"), parse("#5a3aa0"), parse("#e8def6")),
        intArrayOf(parse("#231a14"), parse("#c98a5a"), parse("#5a3424"), parse("#ead2b3")),
        intArrayOf(parse("#101a22"), parse("#86b3d1"), parse("#2b4a6a"), parse("#d3e3f0")),
        intArrayOf(parse("#2a1a1f"), parse("#d97a8a"), parse("#7a2a3a"), parse("#f0d4d9")),
        intArrayOf(parse("#0f1212"), parse("#9ca39a"), parse("#3a4240"), parse("#e2e6e1")),
        intArrayOf(parse("#1b2218"), parse("#b5c47a"), parse("#4a5a2a"), parse("#dde6c0")),
        intArrayOf(parse("#1d1410"), parse("#e0a060"), parse("#8a4020"), parse("#f3d9b8")),
    )

    enum class Layout { CIRCLE, SPLIT, BARS, ARC, MESH, SQUARE, HORIZON, RAYS }

    /** Eight layouts mirrored from `COVER_LAYOUTS` in the HTML. Index order matters. */
    val LAYOUTS: List<Layout> = listOf(
        Layout.CIRCLE,
        Layout.SPLIT,
        Layout.BARS,
        Layout.ARC,
        Layout.MESH,
        Layout.SQUARE,
        Layout.HORIZON,
        Layout.RAYS,
    )

    /** FNV-1a 32-bit, matching `hashStr` from the HTML prototype byte-for-byte. */
    fun hashStr(s: String): Long {
        var h = FNV_OFFSET_32
        for (i in s.indices) {
            h = h xor s[i].code.toLong()
            // Math.imul-style 32-bit multiply: mask after each step.
            h = (h * FNV_PRIME_32) and BITMASK_32
        }
        return h
    }

    /** Pick an element from [items] deterministically by `seed`. */
    fun <T> pick(items: List<T>, seed: String): T {
        if (items.isEmpty()) error("pick called on empty list")
        val idx = (hashStr(seed) % items.size).toInt()
        return items[idx]
    }

    /** Random in `[lo, hi)` derived from `seed`. Matches `rnd(seed, lo, hi)`. */
    fun rnd(seed: String, lo: Float, hi: Float): Float {
        val frac = (hashStr(seed) % 1000L).toFloat() / 1000f
        return lo + frac * (hi - lo)
    }

    /** Convenience: returns the 4-color palette for [id]. */
    fun coverBg(id: String?): IntArray = pick(PALETTES, id ?: "")

    /** Layout choice for [seed] — uses `+ "L"` salt to decouple from palette. */
    fun layoutFor(seed: String): Layout = pick(LAYOUTS, seed + "L")

    private fun parse(hex: String): Int = Color.parseColor(hex)
}
