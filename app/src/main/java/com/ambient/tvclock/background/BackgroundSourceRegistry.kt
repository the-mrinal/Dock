package com.ambient.tvclock.background

/**
 * Every background the dock knows how to paint, keyed by the id stored in
 * preferences.
 *
 * Sources are built lazily and memoized: registering a source that talks to a
 * server must not cost anything until the user actually selects it. Adding a
 * new background is one `register` call at construction — no controller edit,
 * no enum edit, nothing downstream to update.
 */
class BackgroundSourceRegistry private constructor(
    private val factories: Map<String, () -> BackgroundSource>,
) {

    private val instances = LinkedHashMap<String, BackgroundSource>()

    val ids: Set<String> get() = factories.keys

    fun isKnown(id: String): Boolean = factories.containsKey(id)

    /** The source for [id], building it on first use. Null if unregistered. */
    fun get(id: String): BackgroundSource? {
        instances[id]?.let { return it }
        val built = factories[id]?.invoke() ?: return null
        instances[id] = built
        return built
    }

    /** Only the sources that have actually been built — the ones with state
     *  worth starting, stopping or notifying. */
    fun instantiated(): Collection<BackgroundSource> = instances.values.toList()

    class Builder {
        private val factories = LinkedHashMap<String, () -> BackgroundSource>()

        fun register(id: String, factory: () -> BackgroundSource) = apply {
            require(!factories.containsKey(id)) { "background source already registered: $id" }
            factories[id] = factory
        }

        fun build() = BackgroundSourceRegistry(LinkedHashMap(factories))
    }

    companion object {
        fun builder() = Builder()
    }
}
