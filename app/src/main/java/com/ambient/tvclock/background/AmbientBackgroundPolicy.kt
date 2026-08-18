package com.ambient.tvclock.background

/**
 * What the screensaver shows.
 *
 * Ambient mode used to be unconditionally black: `setAmbient(true)` faded the
 * background out no matter which source was active, and `evaluate()` returned
 * early while ambient. That is why the screensaver was a black screen even
 * with a wallpaper selected.
 *
 * Now it is a decision, and a separate one from the awake background — you can
 * run album art while music plays and still have the wallpaper as your
 * screensaver.
 */
fun interface AmbientBackgroundPolicy {

    /**
     * The source id to paint while the dashboard is idle, or null to go black.
     *
     * @param awakeSourceId what would have been showing had the dock stayed awake.
     */
    fun ambientSourceId(awakeSourceId: String): String?

    companion object {
        /** The original behaviour: idle means a true-black wall. */
        val AlwaysBlack = AmbientBackgroundPolicy { null }

        /** Keep painting whatever was already up. */
        val KeepAwakeSource = AmbientBackgroundPolicy { it }

        /**
         * Always show one specific source while idle, whatever is showing
         * awake — "use my wallpaper as the screensaver".
         */
        fun fixed(sourceId: String) = AmbientBackgroundPolicy { sourceId }

        /**
         * Resolve a stored preference value into a policy.
         *
         * [BLACK] is the default so an existing dock behaves exactly as it did
         * before this feature landed; nothing changes until the user asks.
         */
        fun fromPreference(value: String?): AmbientBackgroundPolicy = when (value) {
            null, "", BLACK -> AlwaysBlack
            SAME_AS_AWAKE -> KeepAwakeSource
            else -> fixed(value)
        }

        const val BLACK = "black"
        const val SAME_AS_AWAKE = "same"
    }
}
