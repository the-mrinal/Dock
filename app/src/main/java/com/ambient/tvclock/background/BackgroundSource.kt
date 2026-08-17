package com.ambient.tvclock.background

/**
 * Something that decides what the background should be.
 *
 * Before this existed, [com.ambient.tvclock.BackgroundController] instantiated
 * `UnsplashBackgroundSource` directly and dispatched on a three-valued enum, so
 * every new kind of background meant editing the enum *and* four `when` blocks
 * inside the controller. Now a source is registered once and the controller
 * never learns its name.
 *
 * Threading: the whole API is main-thread. Implementations that touch the
 * network do so on their own executor and post results back before calling the
 * listener.
 *
 * Lifecycle mirrors the activity: [start] on foreground, [stop] on background,
 * with [pause]/[resume] as the controller switches between sources. A paused
 * source must do no work and emit nothing.
 */
interface BackgroundSource {

    /** Stable id, matching the value stored in the background preferences. */
    val id: String

    /**
     * Begin producing images. The listener may be called immediately with a
     * cached image and any number of times afterwards.
     */
    fun start(listener: (BackgroundImage) -> Unit)

    /** Release everything; [start] may be called again afterwards. */
    fun stop()

    /** Stop emitting, keeping state so [resume] is cheap. */
    fun pause()

    fun resume()

    /** The image to paint right now without advancing anything, if known. */
    fun current(): BackgroundImage?

    /** Advance to the next image, if this source has more than one. */
    fun shuffleNow() {}

    /** A preference this source cares about changed. */
    fun onSettingChanged(key: String) {}
}
