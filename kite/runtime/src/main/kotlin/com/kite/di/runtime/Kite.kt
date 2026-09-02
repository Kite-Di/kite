package com.kite.di.runtime

import android.app.Application
import android.content.Context
import com.kite.di.graph.RuntimeState
import com.kite.di.runtime.android.AndroidScopes
import com.kite.di.runtime.observe.GraphEvent
import com.kite.di.runtime.observe.GraphEvents
import java.io.Closeable
import java.util.ServiceLoader
import kotlinx.coroutines.flow.SharedFlow
import kotlin.reflect.KClass

class KiteConfig(
    /** Effective only when the debug inspector artifact is on the classpath. */
    val inspectorEnabled: Boolean = true,
    val inspectorPort: Int = 8394,
)

/**
 * Handle for a manually opened custom scope; [close] closes the scope subtree.
 *
 * Resolve against the scope with [get] — or pass the handle as `owner` to
 * [Kite.get]; both hit the same scope node.
 */
class ScopeHandle internal constructor(val node: ScopeNode, private val onClose: () -> Unit) : Closeable {

    fun <T : Any> get(type: Class<T>, qualifier: String? = null): T =
        Kite.requireContainer().resolve(Key(type, qualifier), node)

    inline fun <reified T : Any> get(qualifier: String? = null): T = get(T::class.java, qualifier)

    override fun close() = onClose()
}

/**
 * Public entry point. Call [init] once from `Application.onCreate()`; everything
 * else is map lookups over the KSP-generated registries.
 */
object Kite {

    @Volatile
    private var container: Container? = null
    private val scopeOwners = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<Any, ScopeNode>())

    /**
     * Loads the generated `MergedRegistry`, builds the scope tree, starts the
     * inspector (debug). Call the generated `Graph.start(app, …)` instead of this —
     * it forwards here and supplies [arguments], the graph-argument instance
     * bindings bubbled up from leaf constructor parameters.
     */
    fun init(
        app: Application,
        config: KiteConfig = KiteConfig(),
        arguments: List<BindingRecord> = emptyList(),
    ) {
        synchronized(this) {
            if (container == null) {
                val builtIns = listOf(
                    BindingRecord(
                        key = Key(Application::class.java),
                        extraKeys = listOf(Key(Context::class.java)),
                        factory = InstanceFactory(app),
                        scopeLevel = 0,
                        scopeName = "Singleton",
                        declaration = "built-in (application)",
                    ),
                ) + arguments
                val created = Container(loadMergedRegistry(), ScopeTree(), builtIns)
                // The Application instance is a well-known singleton, pre-cached.
                created.scopeTree.root.instances[Key(Application::class.java)] = app
                container = created
                if (config.inspectorEnabled) startInspector(app, config)
            }
            // Outside the creation guard, idempotent per application instance: a
            // process normally has one Application, but tests (Robolectric) create a
            // fresh one per test — each needs the lifecycle callbacks installed.
            AndroidScopes.install(app)
        }
    }

    fun <T : Any> get(type: Class<T>, qualifier: String? = null, owner: Any? = null): T {
        val c = requireContainer()
        // Keys are Class references (boxed in Key's constructor), so lookups are
        // identity-safe under R8 renaming — no name-based mapping needed.
        return c.resolve(Key(type, qualifier), scopeOf(owner))
    }

    fun <T : Any> get(type: KClass<T>, qualifier: String? = null, owner: Any? = null): T =
        get(type.java, qualifier, owner)

    inline fun <reified T : Any> get(qualifier: String? = null, owner: Any? = null): T =
        get(T::class.java, qualifier, owner)

    /** Opens a custom scope. Declare its bindings' lifetime with a `scope <fqn> -> <Name>:<level>` rule. */
    fun openScope(
        scopeId: ScopeId,
        parent: ScopeId = ScopeId.App,
        name: String = "Custom",
        level: Int = 99,
    ): ScopeHandle {
        val tree = requireContainer().scopeTree
        val node = tree.open(scopeId, name, level, parent)
        lateinit var handle: ScopeHandle
        handle = ScopeHandle(node) {
            unregisterOwner(handle)
            tree.close(scopeId)
        }
        // The handle doubles as an owner: Kite.get(..., owner = handle) and
        // scoped member injection resolve against this scope.
        registerOwner(handle, node)
        return handle
    }

    // ---- internal wiring -------------------------------------------------------

    internal fun requireContainer(): Container = container
        ?: throw KiteException("Kite.init(application) was not called — call it from Application.onCreate().")

    /** The scope an owner object (Activity/Fragment) resolves against; app scope otherwise. */
    fun scopeOf(owner: Any?): ScopeNode {
        val c = requireContainer()
        if (owner == null) return c.scopeTree.root
        return scopeOwners[owner] ?: c.scopeTree.root
    }

    internal fun registerOwner(owner: Any, node: ScopeNode) {
        scopeOwners[owner] = node
    }

    internal fun unregisterOwner(owner: Any) {
        scopeOwners.remove(owner)
    }

    private fun loadMergedRegistry(): List<BindingRegistry> {
        val fqn = "com.kite.di.generated.MergedRegistry"
        val clazz = try {
            Class.forName(fqn)
        } catch (e: ClassNotFoundException) {
            throw KiteException(
                "Generated registry $fqn not found. Apply the `com.kite.di` " +
                    "Gradle plugin in the application module — it wires the KSP processor " +
                    "that infers the graph and generates the registry."
            )
        }
        val instance = clazz.getDeclaredField("INSTANCE").get(null)
        @Suppress("UNCHECKED_CAST")
        return clazz.getDeclaredMethod("load").invoke(instance) as List<BindingRegistry>
    }

    private fun startInspector(app: Application, config: KiteConfig) {
        val access = object : InspectorRuntimeAccess {
            override fun runtimeState(): RuntimeState {
                val tree = requireContainer().scopeTree
                return RuntimeState(openScopes = tree.openScopes(), instances = tree.instances())
            }

            override val events: SharedFlow<GraphEvent> get() = GraphEvents.flow
        }
        val hook = ServiceLoader.load(InspectorHook::class.java, InspectorHook::class.java.classLoader)
            .firstOrNull() ?: return // release build: no inspector artifact, nothing to start
        // Tracking paths switch on only if the hook really started: an inspector on
        // the classpath of a JVM unit test declines, and nothing should pay for it.
        GraphEvents.tracing = hook.start(app, config, access)
    }
}

/**
 * Implemented by `:kite:inspector` (registered via ServiceLoader) and absent in
 * release builds. The runtime never depends on the inspector — only the reverse.
 */
interface InspectorHook {
    /** Returns whether it actually started — a hook may decline (app not debuggable). */
    fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess): Boolean
    fun stop()
}

/** The runtime surface the inspector reads: live scope/instance state and events. */
interface InspectorRuntimeAccess {
    fun runtimeState(): RuntimeState
    val events: SharedFlow<GraphEvent>
}
