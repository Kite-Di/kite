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

private fun registry(vararg records: BindingRecord) = object : BindingRegistry {
    override fun bindings(): List<BindingRecord> = records.toList()
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
    fun `set factory resolves elements through their own keys and scopes`() {
        val configFactory = ConfigFactory()
        val setKey = Key(Set::class.java, element = RepoIface::class.java)
        val container = Container(
            listOf(
                registry(
                    singletonConfig(configFactory),
                    BindingRecord(REPO, RepoFactory(), declaration = "Repo"), // unscoped element
                    BindingRecord(
                        key = setKey,
                        factory = SetFactory(listOf(REPO, CONFIG)),
                        declaration = "Set<RepoIface> (2 implementations)",
                    ),
                )
            )
        )
        val set: Set<Any> = container.resolve(setKey, container.scopeTree.root)
        assertEquals(2, set.size)
        // elements resolve through the container: the singleton element is the shared instance
        assertTrue(container.resolve<Config>(CONFIG, container.scopeTree.root) in set)
        assertEquals(1, configFactory.created)
        // the set itself is unscoped: fresh set per resolution, unscoped elements fresh too
        val again: Set<Any> = container.resolve(setKey, container.scopeTree.root)
        assertNotSame(set, again)
        assertEquals(1, configFactory.created, "singleton element must not be rebuilt")
    }

    @Test
    fun `graph argument enters the container as an instance binding`() {
        val apiKey = Key(String::class.java, qualifier = "apiKey")
        val container = Container(
            listOf(registry(BindingRecord(apiKey, InstanceFactory("demo-key"), scopeLevel = 0, scopeName = "Singleton", declaration = "Graph.start(apiKey)")))
        )
        assertEquals("demo-key", container.resolve(apiKey, container.scopeTree.root))
        assertEquals("apiKey@kotlin.String", apiKey.id)
    }

    @Test
    fun `duplicate binding across registries fails at construction`() {
        val e = assertFailsWith<KiteException> {
            Container(listOf(registry(singletonConfig()), registry(singletonConfig())))
        }
        assertTrue("Duplicate binding" in e.message!!)
    }
}
