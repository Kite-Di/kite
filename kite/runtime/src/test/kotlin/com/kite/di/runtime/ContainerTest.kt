package com.kite.di.runtime

import com.kite.di.graph.Provenance
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Hand-written stand-ins for what the KSP processor generates.
private class Config
private interface RepoIface
private class Repo(val config: Config) : RepoIface
private class Presenter(val repo: Repo, val freshConfig: Config)

private val CONFIG = Key(Config::class.java)
private val REPO = Key(Repo::class.java)
private val REPO_IFACE = Key(RepoIface::class.java)
private val PRESENTER = Key(Presenter::class.java)

private class ConfigFactory : Factory<Config> {
    var created = 0
    override fun create(resolver: Resolver, scope: ScopeNode): Config {
        created++
        return Config()
    }
}

private class RepoFactory : Factory<Repo> {
    override fun create(resolver: Resolver, scope: ScopeNode): Repo =
        Repo(resolver.resolve(CONFIG, scope))
}

private class PresenterFactory : Factory<Presenter> {
    override fun create(resolver: Resolver, scope: ScopeNode): Presenter =
        Presenter(resolver.resolve(REPO, scope), resolver.resolve(CONFIG, scope))
}

// Framework-instantiated target with an @Inject field, as the processor would see it.
private class Screen {
    lateinit var config: Config
}

private class ScreenMemberInjector : MemberInjector<Screen> {
    override fun inject(target: Screen, resolver: Resolver, scope: ScopeNode) {
        target.config = resolver.resolve(CONFIG, scope)
    }
}

private fun registry(vararg records: BindingRecord) = object : BindingRegistry {
    override fun bindings(): List<BindingRecord> = records.toList()
}

private fun registryWithMembers(
    injectors: Map<Class<*>, MemberInjector<*>>,
    vararg records: BindingRecord,
) = object : BindingRegistry {
    override fun bindings(): List<BindingRecord> = records.toList()
    override fun memberInjectors(): Map<Class<*>, MemberInjector<*>> = injectors
}

class ContainerTest {

    private fun singletonConfig(factory: ConfigFactory = ConfigFactory()) =
        BindingRecord(CONFIG, factory, scopeLevel = 0, scopeName = "Singleton", declaration = "Config")

    @Test
    fun `singleton is cached, unscoped is fresh per injection`() {
        val configFactory = ConfigFactory()
        val container = Container(
            listOf(
                registry(
                    singletonConfig(configFactory),
                    BindingRecord(REPO, RepoFactory(), declaration = "Repo"), // unscoped
                )
            )
        )
        val root = container.scopeTree.root
        val repo1: Repo = container.resolve(REPO, root)
        val repo2: Repo = container.resolve(REPO, root)
        assertNotSame(repo1, repo2, "unscoped binding must create a new instance per injection")
        assertSame(repo1.config, repo2.config, "singleton dependency must be shared")
        assertEquals(1, configFactory.created)
    }

    @Test
    fun `extra keys resolve to the same record and same singleton instance`() {
        val container = Container(
            listOf(
                registry(
                    BindingRecord(
                        REPO, RepoFactory(), extraKeys = listOf(REPO_IFACE),
                        scopeLevel = 0, scopeName = "Singleton", declaration = "Repo",
                    ),
                    singletonConfig(),
                )
            )
        )
        val root = container.scopeTree.root
        assertSame(container.resolve<Repo>(REPO, root), container.resolve(REPO_IFACE, root))
    }

    @Test
    fun `activity scoped instance lives in the activity scope and dies with it`() {
        val container = Container(
            listOf(
                registry(
                    BindingRecord(REPO, RepoFactory(), scopeLevel = 1, scopeName = "ActivityScoped", declaration = "Repo"),
                    singletonConfig(),
                )
            )
        )
        val activityScope = container.scopeTree.open(ScopeId("MainActivity@1"), "ActivityScoped", 1)
        val a: Repo = container.resolve(REPO, activityScope)
        assertSame(a, container.resolve(REPO, activityScope))

        container.scopeTree.close(ScopeId("MainActivity@1"))
        val reopened = container.scopeTree.open(ScopeId("MainActivity@1"), "ActivityScoped", 1)
        assertNotSame(a, container.resolve(REPO, reopened), "closed scope must drop its instances")
    }

    @Test
    fun `resolving activity scoped from app scope fails with provenance`() {
        val container = Container(
            listOf(
                registry(
                    BindingRecord(
                        REPO, RepoFactory(), scopeLevel = 1, scopeName = "ActivityScoped",
                        declaration = "Repo", provenance = Provenance(":app", "app/src/Repo.kt", 8),
                    ),
                    singletonConfig(),
                )
            )
        )
        val e = assertFailsWith<KiteException> {
            container.resolve<Repo>(REPO, container.scopeTree.root)
        }
        assertTrue("app/src/Repo.kt:8" in e.message!!, "message must carry provenance: ${e.message}")
        assertTrue("ActivityScoped" in e.message!!)
    }

    @Test
    fun `missing binding reports near-miss with other qualifier`() {
        val container = Container(
            listOf(registry(singletonConfig().let {
                BindingRecord(Key(Config::class.java, "prod"), ConfigFactory(), scopeLevel = 0, scopeName = "Singleton")
            }))
        )
        val e = assertFailsWith<KiteException> {
            container.resolve<Config>(CONFIG, container.scopeTree.root)
        }
        assertTrue("prod@${Config::class.java.name}" in e.message!!, "near-miss should be suggested: ${e.message}")
    }

    @Test
    fun `provider gives fresh instances and lazy memoizes`() {
        val container = Container(
            listOf(
                registry(
                    BindingRecord(REPO, RepoFactory(), declaration = "Repo"),
                    singletonConfig(),
                )
            )
        )
        val root = container.scopeTree.root
        val provider = container.provider<Repo>(REPO, root)
        assertNotSame(provider.get(), provider.get())
        val lazy = container.deferred<Repo>(REPO, root)
        assertSame(lazy.get(), lazy.get())
    }

    @Test
    fun `provider is a function type and lazy is a kotlin Lazy`() {
        val container = Container(
            listOf(
                registry(
                    BindingRecord(REPO, RepoFactory(), declaration = "Repo"), // unscoped
                    singletonConfig(),
                )
            )
        )
        val root = container.scopeTree.root
        // Provider<T> : () -> T — what generated code passes to a `deps: () -> Repo` parameter.
        val fresh: () -> Repo = container.provider(REPO, root)
        assertNotSame(fresh(), fresh())
        // Lazy<T> : kotlin.Lazy<T> — what generated code passes to a `dep: Lazy<Repo>` parameter.
        val memo: kotlin.Lazy<Repo> = container.deferred(REPO, root)
        assertTrue(!memo.isInitialized(), "lazy must not resolve before first access")
        assertSame(memo.value, memo.value)
        assertTrue(memo.isInitialized())
    }

    @Test
    fun `concurrent singleton resolution creates exactly one instance`() {
        val configFactory = ConfigFactory()
        val container = Container(listOf(registry(singletonConfig(configFactory))))
        val root = container.scopeTree.root
        val threads = 16
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val results = (0 until threads).map {
            pool.submit<Config> {
                ready.countDown()
                go.await()
                container.resolve(CONFIG, root)
            }
        }
        ready.await()
        go.countDown()
        val instances = results.map { it.get() }.toSet()
        pool.shutdown()
        assertEquals(1, instances.size)
        assertEquals(1, configFactory.created)
    }

    @Test
    fun `set factory aggregates contributions in declaration order with live dependencies`() {
        val configFactory = ConfigFactory()
        val setKey = Key(Set::class.java, element = Repo::class.java)
        val container = Container(
            listOf(
                registry(
                    singletonConfig(configFactory),
                    BindingRecord(
                        key = setKey,
                        factory = SetFactory(listOf(RepoFactory(), RepoFactory())),
                        declaration = "Set<Repo> (2 contributions)",
                    ),
                )
            )
        )
        val set: Set<Repo> = container.resolve(setKey, container.scopeTree.root)
        assertEquals(2, set.size)
        // contributions resolved their own singleton dependency through the container
        assertEquals(1, configFactory.created)
        assertSame(set.first().config, set.last().config)
        // unscoped set: a fresh set (and fresh elements) per resolution
        assertNotSame(set, container.resolve(setKey, container.scopeTree.root))
    }

    @Test
    fun `map factory aggregates entries in declaration order with live dependencies`() {
        val configFactory = ConfigFactory()
        val mapKey = Key(Map::class.java, mapValue = Repo::class.java)
        val container = Container(
            listOf(
                registry(
                    singletonConfig(configFactory),
                    BindingRecord(
                        key = mapKey,
                        factory = MapFactory(mapOf("primary" to RepoFactory(), "backup" to RepoFactory())),
                        declaration = "Map<String, Repo> (2 entries)",
                    ),
                )
            )
        )
        val map: Map<String, Repo> = container.resolve(mapKey, container.scopeTree.root)
        assertEquals(listOf("primary", "backup"), map.keys.toList(), "iteration follows declaration order")
        // entries resolved their own singleton dependency through the container
        assertEquals(1, configFactory.created)
        assertSame(map["primary"]!!.config, map["backup"]!!.config)
        // unscoped map: a fresh map (and fresh entries) per resolution
        assertNotSame(map, container.resolve(mapKey, container.scopeTree.root))
        // keys are equal by (type, qualifier, mapValue) — different value class, different key
        assertEquals(mapKey, Key(Map::class.java, mapValue = Repo::class.java))
        assertTrue(Key(Map::class.java, mapValue = Config::class.java) != mapKey)
        assertEquals("kotlin.collections.Map<kotlin.String,${Repo::class.java.name}>", mapKey.id)
    }

    @Test
    fun `member injection fills fields from the target's scope`() {
        val configFactory = ConfigFactory()
        val container = Container(
            listOf(registryWithMembers(mapOf(Screen::class.java to ScreenMemberInjector()), singletonConfig(configFactory)))
        )
        // This is what AndroidScopes does for Activities/Fragments before onCreate:
        val screen = Screen()
        assertTrue(container.hasMemberInjector(Screen::class.java))
        container.injectMembers(screen, container.scopeTree.root)
        assertSame(screen.config, container.resolve(CONFIG, container.scopeTree.root))
        assertEquals(1, configFactory.created)
    }

    @Test
    fun `unknown member-injection target is guarded and reports helpfully`() {
        val container = Container(listOf(registry(singletonConfig())))
        // hasMemberInjector is the guard auto-injection uses to skip plain targets.
        assertTrue(!container.hasMemberInjector(Screen::class.java))
        val e = assertFailsWith<KiteException> {
            container.injectMembers(Screen(), container.scopeTree.root)
        }
        assertTrue("@Inject" in e.message!!, "error should point at @Inject fields: ${e.message}")
    }

    @Test
    fun `duplicate binding across registries fails at construction`() {
        val e = assertFailsWith<KiteException> {
            Container(listOf(registry(singletonConfig()), registry(singletonConfig())))
        }
        assertTrue("Duplicate binding" in e.message!!)
    }
}
