package com.kite.di.processor.validate

import com.kite.di.processor.harness.ksp
import com.kite.di.processor.harness.upstream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Modules that cannot see each other meet only in the application's merge — the one
 * compilation that can tell when they claimed the same key.
 */
class SiblingModulesTest {

    private val api = upstream(
        ":api",
        "Api.kt" to """
            package com.example.api
            interface Gateway
        """,
    )

    private fun impl(module: String, pkg: String, name: String, mark: String = "") = upstream(
        module,
        "$name.kt" to """
            package $pkg
            import com.example.api.Gateway
            $mark class $name : Gateway
        """,
        upstream = listOf(api),
    )

    @Test
    fun `two sibling modules binding one interface fail the application's build`() {
        val run = ksp(
            "App.kt" to """
                package com.example.app
                import androidx.lifecycle.ViewModel
                import com.example.api.Gateway
                class PayViewModel(val gateway: Gateway) : ViewModel()
            """,
            upstream = listOf(api, impl(":stripe", "com.example.stripe", "StripeGateway"), impl(":paypal", "com.example.paypal", "PayPalGateway")),
        )
        // Used to pass, and the merged container threw "Duplicate binding" at startup.
        val error = run.errors.singleOrNull { "Gateway" in it }
        assertTrue(error != null, run.log)
        assertTrue(":stripe" in error && ":paypal" in error, error)
    }

    @Test
    fun `the conflict is reported even when nothing consumes the interface`() {
        val run = ksp(
            "App.kt" to """
                package com.example.app
                class Shell
            """,
            upstream = listOf(api, impl(":stripe", "com.example.stripe", "StripeGateway"), impl(":paypal", "com.example.paypal", "PayPalGateway")),
        )
        // The merged registry carries two records for one key either way.
        assertTrue(run.errors.any { "Gateway" in it && ":stripe" in it && ":paypal" in it }, run.log)
    }

    @Test
    fun `one sibling binding the interface is fine`() {
        val run = ksp(
            "App.kt" to """
                package com.example.app
                import androidx.lifecycle.ViewModel
                import com.example.api.Gateway
                class PayViewModel(val gateway: Gateway) : ViewModel()
            """,
            upstream = listOf(api, impl(":stripe", "com.example.stripe", "StripeGateway")),
        )
        assertEquals(emptyList(), run.errors, run.log)
    }
}
