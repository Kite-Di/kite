package com.kite.demo

import android.app.Application
import com.kite.demo.di.closeSession
import com.kite.demo.di.openSession
import com.kite.demo.di.sessionCart
import com.kite.di.generated.Graph
import com.kite.di.runtime.KiteException
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Runs the real Kite (generated MergedRegistry, via the generated Graph façade) on
 * the JVM: android.jar stubs no-op via returnDefaultValues, so the graph and the
 * scope work without a device. The cart's lifetime is one line in GraphRules.kt:
 * `@Scoped(DefaultSessionCart::class, "Session")`; everything here is generated.
 */
class CustomScopeTest {

    @Test
    fun `a scope of our own caches while open and starts over when opened again`() {
        val app = Application()
        Graph.start(app, apiKey = "test-key", timeoutMillis = 1)

        openSession()
        val cart = app.sessionCart().value
        cart.add("book")
        assertSame("one cart per open session", cart, app.sessionCart().value)

        openSession() // a new login: everything in the scope is new
        assertNotSame(cart, app.sessionCart().value)

        closeSession()
        // Nothing to resolve in: the one failure the build cannot see, with the fix in it.
        val e = assertThrows(KiteException::class.java) { app.sessionCart().value }
        assert("call openSession() first" in e.message!!) { e.message!! }
    }
}
