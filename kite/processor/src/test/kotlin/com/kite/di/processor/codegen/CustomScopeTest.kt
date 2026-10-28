package com.kite.di.processor.codegen

import com.kite.di.processor.harness.compileErrors
import com.kite.di.processor.harness.ksp
import com.kite.di.processor.harness.upstream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A scope of the application's own (ADR 15 §4): one line in GraphRules.kt, and
 * everything else — opening, closing, lookups, checks — follows from the name.
 */
class CustomScopeTest {

    private fun library(code: String, module: String = ":feature") =
        ksp("Graph.kt" to code, module = module, aggregate = false)

    @Test
    fun `naming a scope generates how to open and close it`() {
        val run = library(
            """
            package com.example.di
            import com.kite.di.rules.Scoped
            interface Cart
            class RealCart : Cart
            @Scoped(RealCart::class, "Session")
            private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        val scopes = run.file("Feature_Scopes.kt")
        assertTrue("package com.example.di" in scopes, scopes)
        assertTrue("public fun openSession() {" in scopes && "Kite.openScope(\"Session\"," in scopes, scopes)
        assertTrue("public fun closeSession() {" in scopes && "Kite.closeScope(\"Session\")" in scopes, scopes)
        // Its classes are looked up like any app-wide binding.
        val accessors = run.generated.filterKeys { it.endsWith("_Accessors.kt") }.values.joinToString()
        assertTrue("fun Context.cart(): Lazy<Cart>" in accessors, accessors)
    }

    @Test
    fun `a lookup into the scope is never kept past it`() {
        val run = library(
            """
            package com.example
            import com.kite.di.rules.Fresh
            import com.kite.di.rules.Scoped
            interface Cart
            class RealCart : Cart
            interface Greeter
            @Fresh class RealGreeter(val cart: Cart) : Greeter
            interface Clock
            class RealClock : Clock
            @Scoped(RealCart::class, "Session")
            private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        val accessors = run.generated.filterKeys { it.endsWith("_Accessors.kt") }.values.joinToString("\n")
        // In the scope, or reaching it through something unscoped: looked up on every read.
        assertTrue("fun Context.cart(): Lazy<Cart> = kiteLookupLive(" in accessors, accessors)
        assertTrue("fun Context.greeter(): Lazy<Greeter> = kiteLookupLive(" in accessors, accessors)
        // An app-wide singleton is resolved once, as before.
        assertTrue("fun Context.clock(): Lazy<Clock> = kiteLookup(" in accessors, accessors)
    }

    @Test
    fun `nothing that outlives the scope may hold its classes`() {
        val run = library(
            """
            package com.example
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            class Cart
            class Checkout(val cart: Cart)
            @Root(Checkout::class)
            @Scoped(Cart::class, "Session")
            private object GraphRules
            """,
        )
        assertTrue(
            run.errors.any { "Scope violation" in it && "Checkout" in it && "only while a Session is open" in it && "@Scoped(Checkout::class, \"Session\")" in it },
            run.log,
        )
    }

    @Test
    fun `a scope's classes hold singletons and each other`() {
        val run = library(
            """
            package com.example
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            class Analytics
            class Cart(val analytics: Analytics)
            class Checkout(val cart: Cart)
            @Root(Checkout::class)
            @Scoped(Cart::class, "Session")
            @Scoped(Checkout::class, "Session")
            private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
    }

    @Test
    fun `a scope's classes cannot hold what an Activity owns or another scope's`() {
        fun errorsOf(rules: String) = library(
            """
            package com.example
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            class ScreenState
            class Wallet
            class Cart(val state: ScreenState)
            class Basket(val wallet: Wallet)
            $rules
            private object GraphRules
            """,
        ).errors.joinToString("\n")
        val activity = errorsOf("""@Root(Cart::class) @Scoped(ScreenState::class, "activity") @Scoped(Cart::class, "Session")""")
        assertTrue("Scope violation" in activity && "opens under the app" in activity, activity)
        val other = errorsOf("""@Root(Basket::class) @Scoped(Wallet::class, "Checkout") @Scoped(Basket::class, "Session")""")
        assertTrue("Scope violation" in other && "opened and closed independently" in other, other)
    }

    @Test
    fun `two modules naming one scope mean the same scope`() {
        val cart = upstream(
            ":cart",
            "Cart.kt" to """
                package com.example.cart
                import com.kite.di.rules.Scoped
                interface Cart
                class RealCart : Cart
                @Scoped(RealCart::class, "Session")
                private object GraphRules
            """,
        )
        val run = ksp(
            "App.kt" to """
                package com.example.app
                import com.example.cart.Cart
                import com.kite.di.rules.Root
                import com.kite.di.rules.Scoped
                class Checkout(val cart: Cart)
                @Root(Checkout::class)
                @Scoped(Checkout::class, "Session")
                private object GraphRules
            """,
            upstream = listOf(cart),
        )
        // No level collision, no violation: :cart's Session is :app's Session.
        assertEquals(emptyList(), run.errors, run.log)

        // And code in another module looks the scoped class up with no ceremony.
        val usage = """
            package com.example.feature
            import com.example.cart.cart
            import com.example.cart.openSession
            fun login(context: android.content.Context) { openSession(); val cart by context.cart() }
        """.trimIndent()
        assertEquals(emptyList(), compileErrors("Usage.kt", usage, listOf(cart)))
    }

    @Test
    fun `a module without scopes generates no scope functions`() {
        val run = library(
            """
            package com.example
            interface Repo
            class RealRepo : Repo
            """,
        )
        assertFalse(run.generated.keys.any { it.endsWith("_Scopes.kt") }, run.generated.keys.toString())
    }
}
