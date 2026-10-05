package com.kite.di.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Hand-written stand-ins for what the KSP processor generates.
private interface Store
private class RealStore : Store
private class FakeStore : Store
private class Screen(val store: Store)

private annotation class Primary

private val STORE = Key(Store::class.java)
private val REAL_STORE = Key(RealStore::class.java)
private val PRIMARY_STORE = Key(Store::class.java, mark = Primary::class.java)
private val SCREEN = Key(Screen::class.java)

private class RealStoreFactory : Factory<RealStore> {
    var created = 0
    override fun create(resolver: Resolver, scope: ScopeNode): RealStore {
        created++
        return RealStore()
    }
}

private class ScreenFactory : Factory<Screen> {
    override fun create(resolver: Resolver, scope: ScopeNode): Screen = Screen(resolver.resolve(STORE, scope))
}

private fun registry(vararg records: BindingRecord) = object : BindingRegistry {
    override fun bindings(): List<BindingRecord> = records.toList()
}

class ReplacementTest {

    private val realStore = RealStoreFactory()

    private fun graph(replacements: Map<Key, Any>, builtIns: List<BindingRecord> = emptyList()) = Container(
        listOf(
            registry(
                // Inferred: RealStore bound to its interface, the interface an alias.
                BindingRecord(REAL_STORE, realStore, extraKeys = listOf(STORE), scopeLevel = 0, scopeName = "Singleton", declaration = "RealStore"),
                BindingRecord(SCREEN, ScreenFactory(), declaration = "Screen"),
            )
        ),
        builtIns = builtIns,
        replacements = replacements,
    )

    @Test
    fun `the interface key moves to the replacement, consumers get it`() {
        val fake = FakeStore()
        val container = graph(mapOf(STORE to fake))
        val root = container.scopeTree.root
        assertSame(fake, container.resolve<Store>(STORE, root))
        assertSame(fake, container.resolve<Screen>(SCREEN, root).store)
        assertEquals(0, realStore.created, "the replaced implementation must not be built")
    }

    @Test
    fun `the implementation's own key keeps resolving the real class`() {
        val container = graph(mapOf(STORE to FakeStore()))
        assertTrue(container.resolve<Any>(REAL_STORE, container.scopeTree.root) is RealStore)
    }

    @Test
    fun `replacing the implementation key leaves the interface alias real, in either order`() {
        val fake = RealStore()
        // The replaced record's aliases cache under its key; the replacement must not share that slot.
        val aliasFirst = graph(mapOf(REAL_STORE to fake))
        val root = aliasFirst.scopeTree.root
        val real = aliasFirst.resolve<Store>(STORE, root)
        assertTrue(real !== fake)
        assertSame(fake, aliasFirst.resolve<Any>(REAL_STORE, root))

        val replacedFirst = graph(mapOf(REAL_STORE to fake))
        val root2 = replacedFirst.scopeTree.root
        assertSame(fake, replacedFirst.resolve<Any>(REAL_STORE, root2))
        assertTrue(replacedFirst.resolve<Store>(STORE, root2) !== fake)
    }

    @Test
    fun `a replacement is one shared instance whatever the replaced lifetime`() {
        val fake = FakeStore()
        val container = Container(
            listOf(
                registry(
                    // Activity-scoped and unscoped originals: the replacement is still one instance.
                    BindingRecord(REAL_STORE, realStore, extraKeys = listOf(STORE), scopeLevel = 1, scopeName = "ActivityScoped"),
                    BindingRecord(SCREEN, ScreenFactory()),
                )
            ),
            replacements = mapOf(STORE to fake, SCREEN to Screen(fake)),
        )
        val root = container.scopeTree.root
        val activity = container.scopeTree.open(ScopeId("a"), "ActivityScoped", level = 1)
        // Resolvable from the app scope although the original needed an Activity.
        assertSame(fake, container.resolve<Store>(STORE, root))
        assertSame(fake, container.resolve<Store>(STORE, activity))
        assertSame(container.resolve<Screen>(SCREEN, root), container.resolve<Screen>(SCREEN, activity))
    }

    @Test
    fun `a marked binding is replaced under its mark only`() {
        val fake = FakeStore()
        val container = Container(
            listOf(
                registry(
                    BindingRecord(REAL_STORE, realStore, extraKeys = listOf(PRIMARY_STORE), scopeLevel = 0, scopeName = "Singleton"),
                )
            ),
            replacements = mapOf(PRIMARY_STORE to fake),
        )
        assertSame(fake, container.resolve<Store>(PRIMARY_STORE, container.scopeTree.root))
    }

    @Test
    fun `replacing a type nothing provides fails the start`() {
        val e = assertFailsWith<KiteException> { graph(mapOf(Key(FakeStore::class.java) to FakeStore())) }
        assertTrue(e.message!!.startsWith("replace<com.kite.di.runtime.FakeStore>: nothing in the graph provides it."), e.message)
    }

    @Test
    fun `replacing a built-in fails and points at Graph start`() {
        val arg = Key(String::class.java)
        val builtIn = BindingRecord(arg, InstanceFactory("key"), scopeLevel = 0, scopeName = "Singleton", declaration = "Graph.start(apiKey)")
        val e = assertFailsWith<KiteException> { graph(mapOf(arg to "other"), builtIns = listOf(builtIn)) }
        assertTrue("it is a graph argument" in e.message!!, e.message)
        assertTrue("pass the test's value to Graph.start" in e.message!!, e.message)
    }

    @OptIn(KiteTestApi::class)
    @Test
    fun `KiteTesting rejects an instance of the wrong type`() {
        KiteTesting.reset()
        assertFailsWith<IllegalArgumentException> { KiteTesting.replace(STORE, "not a store") }
        KiteTesting.reset()
    }
}
