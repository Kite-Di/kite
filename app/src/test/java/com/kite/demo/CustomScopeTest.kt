package com.kite.demo

import android.app.Application
import com.kite.demo.di.SessionCart
import com.kite.di.runtime.Kite
import com.kite.di.runtime.ScopeId
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Runs the real Kite (generated MergedRegistry) on the JVM: android.jar stubs
 * no-op via returnDefaultValues, so init/openScope work without a device.
 */
class CustomScopeTest {

    @Test
    fun `custom scope caches its bindings and drops them on close`() {
        Kite.init(Application())

        val session = Kite.openScope(ScopeId("session-1"), name = "SessionScoped", level = 10)
        val cart: SessionCart = session.get()
        cart.add("book")
        // Cached per scope, reachable both through the handle and as an owner.
        assertSame(cart, session.get<SessionCart>())
        assertSame(cart, Kite.get<SessionCart>(owner = session))
        session.close()

        val reopened = Kite.openScope(ScopeId("session-1"), name = "SessionScoped", level = 10)
        try {
            assertNotSame("closed scope must drop its instances", cart, reopened.get<SessionCart>())
        } finally {
            reopened.close()
        }
    }
}
