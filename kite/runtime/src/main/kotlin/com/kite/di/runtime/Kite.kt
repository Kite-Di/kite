package com.kite.di.runtime

import android.app.Application
import android.content.Context
import com.kite.di.graph.Key
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

/** Handle for a manually opened custom scope; [close] closes the scope subtree. */
class ScopeHandle internal constructor(val node: ScopeNode, private val onClose: () -> Unit) : Closeable {
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

    /** Loads the generated `MergedRegistry`, builds the scope tree, starts the inspector (debug). */
    fun init(app: Application, config: KiteConfig = KiteConfig()) {
        if (container != null) return
        synchronized(this) {
            if (container != null) return
            val appKeyFactory = object : Factory<Application> {
                override fun create(resolver: Resolver, scope: ScopeNode): Application = app
            }
            val builtIns = listOf(
                BindingRecord(
                    key = Key("android.app.Application"),
                    extraKeys = listOf(Key("android.content.Context")),
                    factory = appKeyFactory,
                    scopeLevel = 0,
                    scopeName = "Singleton",
                    declaration = "built-in (application)",
                ),
            )
            val created = Container(loadMergedRegistry(), ScopeTree(), builtIns)
            // The Application instance is a well-known singleton, pre-cached.
            created.scopeTree.root.instances[Key("android.app.Application")] = app
            container = created
            AndroidScopes.install(app, this)
            if (config.inspectorEnabled) startInspector(app, config)
        }
    }

    fun <T : Any> get(type: Class<T>, qualifier: String? = null, owner: Any? = null): T {
        val c = requireContainer()
        return c.resolve(keyFor(type, qualifier), scopeOf(owner))
    }

    /** Binding keys use Kotlin FQNs; map the common Java mirrors so `get(String::class.java)` works. */
    private val javaToKotlinTypes = mapOf(
        "java.lang.String" to "kotlin.String",
        "java.lang.Integer" to "kotlin.Int",
        "java.lang.Long" to "kotlin.Long",
        "java.lang.Boolean" to "kotlin.Boolean",
        "java.lang.Double" to "kotlin.Double",
        "java.lang.Float" to "kotlin.Float",
        "java.lang.Short" to "kotlin.Short",
        "java.lang.Byte" to "kotlin.Byte",
        "java.lang.Character" to "kotlin.Char",
        "java.lang.Object" to "kotlin.Any",
        "java.util.List" to "kotlin.collections.List",
        "java.util.Map" to "kotlin.collections.Map",
        "java.util.Set" to "kotlin.collections.Set",
    )

    private fun keyFor(type: Class<*>, qualifier: String?): Key =
        Key(javaToKotlinTypes[type.name] ?: type.name, qualifier)

    fun <T : Any> get(type: KClass<T>, qualifier: String? = null): T = get(type.java, qualifier)

    inline fun <reified T : Any> get(qualifier: String? = null, owner: Any? = null): T =
        get(T::class.java, qualifier, owner)

    /** Fills the @Inject fields of a framework-instantiated object. */
    fun inject(target: Any) {
        requireContainer().injectMembers(target, scopeOf(target))
    }

    /** Opens a custom scope. Declare a matching `@Scope(level = n)` annotation for its bindings. */
    fun openScope(
        scopeId: ScopeId,
        parent: ScopeId = ScopeId.App,
        name: String = "Custom",
        level: Int = 99,
    ): ScopeHandle {
        val tree = requireContainer().scopeTree
        val node = tree.open(scopeId, name, level, parent)
        return ScopeHandle(node) { tree.close(scopeId) }
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
                "Generated registry $fqn not found. Apply the KSP plugin and add " +
                    "`ksp(\"com.kite.di:processor\")` (or the project dependency) " +
                    "with `kite.aggregate=true` in the application module."
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
        hook.start(app, config, access)
    }
}

/**
 * Implemented by `:kite:inspector` (registered via ServiceLoader) and absent in
 * release builds. The runtime never depends on the inspector — only the reverse.
 */
interface InspectorHook {
    fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess)
    fun stop()
}

/** The runtime surface the inspector reads: live scope/instance state and events. */
interface InspectorRuntimeAccess {
    fun runtimeState(): RuntimeState
    val events: SharedFlow<GraphEvent>
}
