package com.kite.di.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

// A type the project does not own, and the project class that supplies it.
private class Transport(val id: Int)

private class Http(private val next: () -> Int) : Supplies<Transport> {
    override val value: Transport = Transport(next())
}

private val HTTP = Key(Http::class.java)
private val TRANSPORT = Key(Transport::class.java)

private class HttpFactory(private val next: () -> Int) : Factory<Http> {
    var created = 0
    override fun create(resolver: Resolver, scope: ScopeNode): Http {
        created++
        return Http(next)
    }
}

private fun supplied(scopeLevel: Int?, scopeName: String?) = BindingRecord(
    key = TRANSPORT,
    factory = SuppliedFactory<Transport>(HTTP),
    scopeLevel = scopeLevel,
    scopeName = scopeName,
    declaration = "Transport (supplied by Http)",
)

private fun registryOf(vararg records: BindingRecord) = object : BindingRegistry {
    override fun bindings(): List<BindingRecord> = records.toList()
}

/**
 * The runtime half of ADR 13. The supplied key and the supplier are two records with
 * two cache entries and one object each, and a lifetime decision on the supplier has
 * to reach the type it supplies — otherwise a `@Fresh` supplier behind a cached
 * singleton key would contradict itself.
 */
class SuppliedFactoryTest {

    private var counter = 0
    private val next = { ++counter }

    @Test
    fun `a singleton supplier supplies one instance`() {
        val httpFactory = HttpFactory(next)
        val container = Container(
            listOf(
                registryOf(
                    BindingRecord(HTTP, httpFactory, scopeLevel = 0, scopeName = "Singleton", declaration = "Http"),
                    supplied(scopeLevel = 0, scopeName = "Singleton"),
                )
            )
        )

        val first: Transport = container.resolve(TRANSPORT, container.scopeTree.root)
        val second: Transport = container.resolve(TRANSPORT, container.scopeTree.root)

        assertSame(first, second)
        assertEquals(1, httpFactory.created)
    }

    @Test
    fun `a fresh supplier supplies a new instance per injection`() {
        val httpFactory = HttpFactory(next)
        val container = Container(
            listOf(
                registryOf(
                    BindingRecord(HTTP, httpFactory, declaration = "Http"), // unscoped: @Fresh
                    supplied(scopeLevel = null, scopeName = null),
                )
            )
        )

        val first: Transport = container.resolve(TRANSPORT, container.scopeTree.root)
        val second: Transport = container.resolve(TRANSPORT, container.scopeTree.root)

        assertNotSame(first, second)
        assertEquals(2, httpFactory.created)
        assertEquals(listOf(1, 2), listOf(first.id, second.id))
    }

    @Test
    fun `the supplier keeps its own key alongside the one it supplies`() {
        val container = Container(
            listOf(
                registryOf(
                    BindingRecord(HTTP, HttpFactory(next), scopeLevel = 0, scopeName = "Singleton", declaration = "Http"),
                    supplied(scopeLevel = 0, scopeName = "Singleton"),
                )
            )
        )

        val supplier: Http = container.resolve(HTTP, container.scopeTree.root)
        val supplied: Transport = container.resolve(TRANSPORT, container.scopeTree.root)

        assertSame(supplier.value, supplied)
    }
}
