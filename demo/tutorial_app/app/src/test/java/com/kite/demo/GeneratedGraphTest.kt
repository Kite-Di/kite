package com.kite.demo

import com.kite.demo.core.analytics.Analytics
import com.kite.demo.core.analytics.CrashReporter
import com.kite.demo.core.analytics.LogcatAnalytics
import com.kite.demo.core.network.ApiClient
import com.kite.demo.core.network.PayloadDecoder
import com.kite.demo.di.StartupTask
import com.kite.demo.ui.GreetingUseCase
import com.kite.demo.ui.SecondPresenter
import com.kite.demo.ui.SessionState
import com.kite.demo.feature.orders.api.OrdersRepository
import com.kite.demo.feature.orders.impl.NetworkOrdersRepository
import com.kite.demo.feature.profile.api.UserRepository
import com.kite.demo.feature.profile.impl.NetworkUserRepository
import com.kite.di.generated.App_BindingRegistry
import com.kite.di.generated.MergedRegistry
import com.kite.di.runtime.BindingRecord
import com.kite.di.runtime.Container
import com.kite.di.runtime.KiteException
import com.kite.di.runtime.observe.GraphEvents
import com.kite.di.runtime.InstanceFactory
import com.kite.di.runtime.Key
import com.kite.di.runtime.ScopeId
import com.kite.di.runtime.SetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the KSP-generated factories/registry of the *inferred* graph on the
 * JVM (no device needed). No domain class in the demo app carries a DI annotation —
 * everything below was inferred from interface/implementation declarations plus the
 * four @Scoped lifetimes in :app's GraphRules.kt and the on-class @Fresh marker
 *. Every lookup here goes through an interface key, the way
 * application code does.
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
    fun `merged registry aggregates one registry per graph module`() {
        // :core:designsystem and the :feature:*:api modules are absent by design —
        // no injectable classes, no Kite plugin, no fragment.
        assertEquals(
            listOf(
                "App_BindingRegistry",
                "CoreAnalytics_BindingRegistry",
                "CoreNetwork_BindingRegistry",
                "FeatureOrdersImpl_BindingRegistry",
                "FeatureProfileImpl_BindingRegistry",
            ),
            MergedRegistry.load().map { it.javaClass.simpleName }.sorted(),
        )
    }

    @Test
    fun `api-impl split - interface from the api module resolves to the impl bound in the impl module`() {
        val container = container()
        // OrdersRepository lives in :feature:orders:api, its sole implementation in
        // :feature:orders:impl — R1 binds across the module boundary, no annotation.
        val repository: OrdersRepository =
            container.resolve(Key(OrdersRepository::class.java), container.scopeTree.root)
        assertTrue(repository is NetworkOrdersRepository)
    }

    @Test
    fun `singleton from generated factory is cached`() {
        val container = container()
        val root = container.scopeTree.root
        // Analytics carries no rule anywhere — singleton is the default (ADR 11).
        val a1: Analytics = container.resolve(Key(Analytics::class.java), root)
        val a2: Analytics = container.resolve(Key(Analytics::class.java), root)
        assertSame(a1, a2)
    }

    @Test
    fun `plain closure classes are singletons by default`() {
        val container = container()
        val root = container.scopeTree.root
        // CrashReporter is a plain class pulled in by closure (R4), no decision
        // anywhere — one shared instance like everything else (ADR 11).
        val r1: CrashReporter = container.resolve(Key(CrashReporter::class.java), root)
        val r2: CrashReporter = container.resolve(Key(CrashReporter::class.java), root)
        assertSame(r1, r2)
    }

    @Test
    fun `a Fresh class gets a new instance per injection`() {
        val container = container()
        val root = container.scopeTree.root
        // GreetingUseCase carries @Fresh on the class — the one lifetime decision
        // that lives in the code it describes (ADR 11).
        val u1: GreetingUseCase = container.resolve(Key(GreetingUseCase::class.java), root)
        val u2: GreetingUseCase = container.resolve(Key(GreetingUseCase::class.java), root)
        assertNotSame(u1, u2)
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
        // `instances()` is board-facing state the runtime only records while an
        // inspector listens (GraphEvents.tracing) — a release build keeps no such
        // ledger. This test observes creation through it, so it opts in.
        GraphEvents.tracing = true
        try {
            assertDeferredUntilFirstCall()
        } finally {
            GraphEvents.tracing = false
        }
    }

    private fun assertDeferredUntilFirstCall() {
        val container = container()
        val root = container.scopeTree.root
        // CrashReporter declares `analytics: () -> Analytics` — a plain function type.
        val reporter: CrashReporter = container.resolve(Key(CrashReporter::class.java), root)
        // Instances are cached under the binding's canonical key — the implementation
        // class — so that resolving by interface or by class shares one instance.
        val analyticsId = LogcatAnalytics::class.java.name
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
