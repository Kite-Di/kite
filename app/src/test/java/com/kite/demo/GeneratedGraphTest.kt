package com.kite.demo

import com.kite.demo.data.Analytics
import com.kite.demo.data.ApiClient
import com.kite.demo.data.CrashReporter
import com.kite.demo.data.NetworkUserRepository
import com.kite.demo.data.PayloadDecoder
import com.kite.demo.data.UserRepository
import com.kite.demo.di.StartupTask
import com.kite.demo.ui.SecondPresenter
import com.kite.demo.ui.SessionState
import com.kite.di.generated.App_BindingRegistry
import com.kite.di.generated.MergedRegistry
import com.kite.di.runtime.BindingRecord
import com.kite.di.runtime.Container
import com.kite.di.runtime.KiteException
import com.kite.di.runtime.InstanceFactory
import com.kite.di.runtime.Key
import com.kite.di.runtime.ScopeId
import com.kite.di.runtime.SetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the KSP-generated factories/registry of the *inferred* graph on the
 * JVM (no device needed). No domain class in the demo app carries a DI annotation —
 * everything below was inferred from declarations plus the @Root/@Scoped decisions
 * on the GraphRules.kt holder object.
 */
class GeneratedGraphTest {

    /** Mirrors what the generated `Graph.start` does: graph arguments become instance bindings. */
    private fun container() = Container(
        MergedRegistry.load(),
        builtIns = listOf(
            BindingRecord(
                key = Key(String::class.java, qualifier = "apiKey"),
                factory = InstanceFactory("demo-key-123"),
                scopeLevel = 0, scopeName = "Singleton",
                declaration = "Graph.start(apiKey)",
            ),
            BindingRecord(
                key = Key(Long::class.java, qualifier = "timeoutMillis"),
                factory = InstanceFactory(5_000L),
                scopeLevel = 0, scopeName = "Singleton",
                declaration = "Graph.start(timeoutMillis)",
            ),
        ),
    )

    @Test
    fun `merged registry loads the app registry`() {
        assertTrue(MergedRegistry.load().any { it is App_BindingRegistry })
    }

    @Test
    fun `singleton from generated factory is cached`() {
        val container = container()
        val root = container.scopeTree.root
        val a1: Analytics = container.resolve(Key(Analytics::class.java), root)
        val a2: Analytics = container.resolve(Key(Analytics::class.java), root)
        assertSame(a1, a2)
    }

    @Test
    fun `sole implementation is bound to its interface without any annotation`() {
        val container = container()
        val root = container.scopeTree.root
        val viaInterface: UserRepository = container.resolve(Key(UserRepository::class.java), root)
        val viaClass: NetworkUserRepository = container.resolve(Key(NetworkUserRepository::class.java), root)
        assertSame(viaClass, viaInterface)
    }

    @Test
    fun `graph argument resolves by parameter-name key`() {
        val container = container()
        val key: String = container.resolve(Key(String::class.java, "apiKey"), container.scopeTree.root)
        assertEquals("demo-key-123", key)
    }

    @Test
    fun `set multibinding record aggregates every StartupTask implementation`() {
        val setRecord = MergedRegistry.load().flatMap { it.bindings() }
            .single { it.key == Key(Set::class.java, element = StartupTask::class.java) }
        assertTrue(setRecord.factory is SetFactory)
        assertEquals("Set<StartupTask> (3 implementations)", setRecord.declaration)
    }

    @Test
    fun `inferred Set of parsers resolves - the strategy key lives on the interface`() {
        val container = container()
        val decoder: PayloadDecoder = container.resolve(Key(PayloadDecoder::class.java), container.scopeTree.root)
        assertEquals(setOf("json", "xml"), decoder.formats())
        assertEquals("json(4 chars)", decoder.decode("json", "{ } "))
    }

    @Test
    fun `function type dependency defers creation until first call`() {
        val container = container()
        val root = container.scopeTree.root
        // CrashReporter declares `analytics: () -> Analytics` — a plain function type.
        val reporter: CrashReporter = container.resolve(Key(CrashReporter::class.java), root)
        val analyticsId = Analytics::class.java.name
        assertTrue(container.scopeTree.instances().none { it.nodeId == analyticsId })
        reporter.report(RuntimeException("boom"))
        assertTrue(container.scopeTree.instances().any { it.nodeId == analyticsId })
    }

    @Test
    fun `kotlin Lazy dependency defers the whole chain until first use`() {
        val container = container()
        // ApiClient declares `client: Lazy<HttpClient>` (kotlin.Lazy). Resolvable on
        // the JVM even though HttpClient → RequestCache needs Android's Context
        // (absent here, provided by Kite.init on device): nothing in that chain
        // is constructed until the first access.
        val api: ApiClient = container.resolve(Key(ApiClient::class.java), container.scopeTree.root)
        val e = assertThrows(KiteException::class.java) { api.fetchUser() }
        assertTrue("Context" in e.message!!)
    }

    @Test
    fun `fragment scoped presenter shares activity scoped session`() {
        val container = container()
        val activity = container.scopeTree.open(ScopeId("MainActivity@x"), "ActivityScoped", 1)
        val fragmentA = container.scopeTree.open(ScopeId("FragA@x"), "FragmentScoped", 2, activity.id)
        val fragmentB = container.scopeTree.open(ScopeId("FragB@x"), "FragmentScoped", 2, activity.id)

        val presenterA: SecondPresenter =
            container.resolve(Key(SecondPresenter::class.java), fragmentA)
        val presenterB: SecondPresenter =
            container.resolve(Key(SecondPresenter::class.java), fragmentB)

        val session: SessionState =
            container.resolve(Key(SessionState::class.java), fragmentA)
        session.visits = 41

        // Different presenters per fragment scope, one session per activity scope.
        assertTrue(presenterA !== presenterB)
        assertEquals(
            41,
            container.resolve<SessionState>(
                Key(SessionState::class.java),
                fragmentB,
            ).visits,
        )
        assertEquals("Session visits so far: 41", presenterB.headline())
    }
}
