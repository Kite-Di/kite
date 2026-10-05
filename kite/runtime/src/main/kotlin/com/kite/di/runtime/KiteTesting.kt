package com.kite.di.runtime

/**
 * Marks the runtime's test surface. Only `com.kitedi:test` opts in; application
 * code that tries to is a compile error, and an app that never references
 * [KiteTesting] has R8 drop it.
 */
@RequiresOptIn(
    message = "Test-only Kite API. Use KiteTestRule and replace() from com.kitedi:test.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class KiteTestApi

/**
 * What `com.kitedi:test` needs from the runtime: discard the running graph, and
 * queue replacements the next `Kite.init` applies (doc 14).
 */
@KiteTestApi
object KiteTesting {

    private val pending = LinkedHashMap<Key, Any>()

    /** Whether a graph is running. */
    val isStarted: Boolean get() = Kite.isStarted

    /** Discards the running graph, if any, and every queued replacement. */
    fun reset() {
        synchronized(Kite) {
            Kite.discard()
            pending.clear()
        }
    }

    /**
     * Queues [instance] to stand behind [key] in the next graph. Throws once a graph
     * is running: replacements apply when the graph is built.
     */
    fun replace(key: Key, instance: Any) {
        synchronized(Kite) {
            if (Kite.isStarted) {
                throw KiteException(
                    "replace<${key.id}> after Graph.start.\n" +
                        "  hint: call replace before Graph.start — the graph is built when it starts."
                )
            }
            require(key.type.isInstance(instance)) {
                "replace<${key.id}>: ${instance.javaClass.name} is not a ${key.type.name}"
            }
            pending[key] = instance
        }
    }

    /** Called by `Kite.init`: hands over the queue and empties it. */
    internal fun takeReplacements(): Map<Key, Any> {
        synchronized(Kite) {
            if (pending.isEmpty()) return emptyMap()
            val taken = LinkedHashMap(pending)
            pending.clear()
            return taken
        }
    }
}
