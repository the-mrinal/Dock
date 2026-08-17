package com.ambient.tvclock.background

/**
 * The thing a background gets painted onto.
 *
 * An interface rather than a direct dependency on `BlurredBackgroundBinder` so
 * the controller can be unit-tested — nothing tested it before this — and so a
 * second host (the system screensaver) can present the same sources without
 * dragging in the dashboard's view hierarchy.
 */
interface BackgroundSurface {

    /** Paint [image], or fade to nothing when it is null. */
    fun show(image: BackgroundImage?)

    /** Enter or leave ambient mode. Whether that means "go black" is the
     *  controller's decision, not the surface's. */
    fun setAmbient(ambient: Boolean)

    fun setBlurEnabled(enabled: Boolean)

    /** Drop the decoded bitmap under memory pressure. */
    fun releaseBitmap()
}
