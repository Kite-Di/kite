package com.kite.demo

import android.app.Application
import com.kite.demo.di.SessionCart
import com.kite.di.generated.Graph
import com.kite.di.runtime.Kite
import com.kite.di.runtime.ScopeId
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Runs the real Kite (generated MergedRegistry, via the generated Graph
 * façade) on the JVM: android.jar stubs no-op via returnDefaultValues, so
 * start/openScope work without a device. SessionCart's lifetime comes from
 * graph.rules: `scope …SessionCart -> Session:10`.
 */
class CustomScopeTest {

    @Test
    fun `custom scope caches its bindings and drops them on close`() {
        Graph.start(Application(), apiKey = "test-key", timeoutMillis = 1)

        val session = Kite.openScope(ScopeId("session-1"), name = "Session", level = 10)
        val cart: SessionCart = session.get()
        cart.add("book")
        // Cached per scope, reachable both through the handle and as an owner.
        assertSame(cart, session.get<SessionCart>())
        assertSame(cart, Kite.get<SessionCart>(owner = session))
        session.close()

        val reopened = Kite.openScope(ScopeId("session-1"), name = "Session", level = 10)
        try {
            assertNotSame("closed scope must drop its instances", cart, reopened.get<SessionCart>())
        } finally {
            reopened.close()
        }
    }
}
