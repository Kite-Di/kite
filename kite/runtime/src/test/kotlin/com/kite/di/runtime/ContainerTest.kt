package com.kite.di.runtime

import com.kite.di.graph.Key
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
private class Repo(val config: Config)
private class Presenter(val repo: Repo, val freshConfig: Config)

private val CONFIG = Key("test.Config")
private val REPO = Key("test.Repo")
private val REPO_IFACE = Key("test.RepoIface")
private val PRESENTER = Key("test.Presenter")

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
                BindingRecord(Key("test.Config", "prod"), ConfigFactory(), scopeLevel = 0, scopeName = "Singleton")
            }))
        )
        val e = assertFailsWith<KiteException> {
            container.resolve<Config>(CONFIG, container.scopeTree.root)
        }
        assertTrue("prod@test.Config" in e.message!!, "near-miss should be suggested: ${e.message}")
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
        val setKey = Key("kotlin.collections.Set<test.Repo>")
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
    fun `duplicate binding across registries fails at construction`() {
        val e = assertFailsWith<KiteException> {
            Container(listOf(registry(singletonConfig()), registry(singletonConfig())))
        }
        assertTrue("Duplicate binding" in e.message!!)
    }
}
