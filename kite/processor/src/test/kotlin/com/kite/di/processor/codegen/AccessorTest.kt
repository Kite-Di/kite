package com.kite.di.processor.codegen

import com.kite.di.processor.harness.compileErrors
import com.kite.di.processor.harness.ksp
import com.kite.di.processor.harness.upstream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Typed lookups (ADR 15): an accessor exists only for a key with a binding, only on
 * receivers that have the binding's scope — so the wrong lookup does not compile.
 */
class AccessorTest {

    private fun accessors(code: String, module: String = ":feature") =
        ksp("Graph.kt" to code, module = module, aggregate = false).generated
            .filterKeys { it.endsWith("_Accessors.kt") }

    /** The receivers `name()` is declared on, read off the generated source. */
    private fun receiversOf(files: Map<String, String>, name: String): Set<String> =
        Regex("""fun (\w+)\.$name\(\)""").findAll(files.values.joinToString("\n")).map { it.groupValues[1] }.toSet()

    @Test
    fun `an app-wide binding can be looked up from anything with a context`() {
        val files = accessors(
            """
            package com.example
            interface Repo
            class RealRepo : Repo
            """,
        )
        assertEquals(setOf("Context", "Fragment", "View"), receiversOf(files, "repo"))
        assertTrue("kiteLookup(Key(Repo::class.java))" in files.values.single(), files.toString())
    }

    @Test
    fun `an activity-scoped binding only from an Activity or a Fragment`() {
        val files = accessors(
            """
            package com.example
            import com.kite.di.rules.Scoped
            interface Session
            class RealSession : Session
            @Scoped(RealSession::class, "activity")
            private object GraphRules
            """,
        )
        assertEquals(setOf("Activity", "Fragment"), receiversOf(files, "session"))
    }

    @Test
    fun `a fragment-scoped binding only from a Fragment`() {
        val files = accessors(
            """
            package com.example
            import com.kite.di.rules.Scoped
            interface Presenter
            class RealPresenter : Presenter
            @Scoped(RealPresenter::class, "fragment")
            private object GraphRules
            """,
        )
        assertEquals(setOf("Fragment"), receiversOf(files, "presenter"))
    }

    @Test
    fun `an unscoped binding takes the scope of what it reaches`() {
        val files = accessors(
            """
            package com.example
            import com.kite.di.rules.Fresh
            import com.kite.di.rules.Scoped
            class Session
            interface Greeter
            @Fresh class RealGreeter(val session: Session) : Greeter
            @Scoped(Session::class, "activity")
            private object GraphRules
            """,
        )
        assertEquals(setOf("Activity", "Fragment"), receiversOf(files, "greeter"))
    }

    @Test
    fun `a plain class is looked up only when it is a root`() {
        val plain = accessors(
            """
            package com.example
            import androidx.lifecycle.ViewModel
            class Cache
            class FeedViewModel(val cache: Cache) : ViewModel()
            """,
        )
        assertTrue(plain.isEmpty(), plain.toString())
        val rooted = accessors(
            """
            package com.example
            import com.kite.di.rules.Root
            class Tracker
            @Root(Tracker::class)
            private object GraphRules
            """,
        )
        assertEquals(setOf("Context", "Fragment", "View"), receiversOf(rooted, "tracker"))
    }

    @Test
    fun `a marked binding is looked up under its mark`() {
        val files = accessors(
            """
            package com.example
            @Retention(AnnotationRetention.SOURCE) annotation class Stripe
            interface Gateway
            @Stripe class StripeGateway : Gateway
            class PlainGateway : Gateway
            """,
        )
        val code = files.values.joinToString("\n")
        assertTrue("fun Context.stripeGateway(): Lazy<Gateway>" in code, code)
        assertTrue("mark = Stripe::class.java" in code, code)
        assertTrue("fun Context.gateway(): Lazy<Gateway>" in code, code)
    }

    @Test
    fun `a supplied type is looked up beside its supplier`() {
        val files = accessors(
            """
            package com.example
            import com.kite.di.runtime.Supplies
            import java.util.concurrent.Executor
            class Pool : Supplies<Executor> { override val value = Executor { it.run() } }
            """,
        )
        assertEquals(setOf("Context", "Fragment", "View"), receiversOf(files, "executor"))
        // Not in java.util.concurrent: nothing may be declared there.
        assertTrue(files.values.single().startsWith("// Generated") && "package com.example\n" in files.values.single(), files.toString())
    }

    @Test
    fun `an internal type gets an internal accessor`() {
        val files = accessors(
            """
            package com.example
            internal interface Repo
            internal class RealRepo : Repo
            """,
        )
        assertTrue("internal fun Context.repo()" in files.values.single(), files.toString())
    }

    @Test
    fun `the wrong lookup does not compile in the module that makes it`() {
        val core = upstream(
            ":core",
            "Core.kt" to """
                package com.example.core
                import com.kite.di.rules.Scoped
                interface Analytics
                class RealAnalytics : Analytics
                interface Session
                class RealSession : Session
                @Scoped(RealSession::class, "activity")
                private object GraphRules
            """,
        )
        val fine = """
            package com.example.app
            import com.example.core.analytics
            import com.example.core.session
            class Service(c: android.content.Context) { val analytics by c.analytics() }
            class Screen : android.app.Activity() { val session by session() }
        """.trimIndent()
        assertEquals(emptyList(), compileErrors("Fine.kt", fine, listOf(core)))

        // A Service has no Activity scope to resolve an activity-scoped Session in.
        val wrongScope = """
            package com.example.app
            import com.example.core.session
            class Service(c: android.content.Context) { val session by c.session() }
        """.trimIndent()
        assertTrue(compileErrors("WrongScope.kt", wrongScope, listOf(core)).isNotEmpty())

        // Nothing binds it: no accessor, no lookup.
        val noBinding = """
            package com.example.app
            import com.example.core.tracker
            class Screen : android.app.Activity() { val tracker by tracker() }
        """.trimIndent()
        assertTrue(compileErrors("NoBinding.kt", noBinding, listOf(core)).isNotEmpty())
    }

    @Test
    fun `a ViewModel whose dependencies reach a fragment gets no Activity adapter`() {
        val run = ksp(
            "Vm.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                import com.kite.di.rules.Scoped
                interface Presenter
                class RealPresenter : Presenter
                class ScreenViewModel(val presenter: Presenter) : ViewModel()
                @Scoped(RealPresenter::class, "fragment")
                private object GraphRules
            """,
        )
        val adapters = run.file("ScreenViewModel_Adapters.kt")
        assertTrue("fun Fragment.screenViewModel()" in adapters, adapters)
        assertFalse("ComponentActivity.screenViewModel()" in adapters, adapters)
    }

    @Test
    fun `a ViewModel may use a scope of your own, from any host`() {
        val run = ksp(
            "Vm.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                import com.kite.di.rules.Scoped
                interface Cart
                class RealCart : Cart
                class CartViewModel(val cart: Cart) : ViewModel()
                @Scoped(RealCart::class, "Session")
                private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        val adapters = run.file("CartViewModel_Adapters.kt")
        assertTrue("ComponentActivity.cartViewModel()" in adapters && "Fragment.cartViewModel()" in adapters, adapters)
    }
}
