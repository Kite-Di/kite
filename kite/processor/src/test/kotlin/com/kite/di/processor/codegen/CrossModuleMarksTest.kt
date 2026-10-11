package com.kite.di.processor.codegen

import com.kite.di.processor.harness.ksp
import com.kite.di.processor.harness.upstream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A mark declared in one module, an implementation carrying it in another, a consumer in a third. */
class CrossModuleMarksTest {

    private val api = upstream(
        ":api",
        "Api.kt" to """
            package com.example.api
            @Retention(AnnotationRetention.SOURCE) annotation class Stripe
            interface Gateway
        """,
    )

    private fun pay(stripped: Boolean) = upstream(
        ":pay",
        "Pay.kt" to """
            package com.example.pay
            import com.example.api.Gateway
            import com.example.api.Stripe
            @Stripe class StripeGateway : Gateway
            class PlainGateway : Gateway
        """,
        upstream = listOf(api),
        options = if (stripped) mapOf("kite.stripProvenance" to "true") else emptyMap(),
    )

    private fun app(pay: com.kite.di.processor.harness.Upstream) = ksp(
        "App.kt" to """
            package com.example.app
            import androidx.lifecycle.ViewModel
            import com.example.api.Gateway
            import com.example.api.Stripe
            class PayViewModel(@Stripe val stripe: Gateway, val plain: Gateway) : ViewModel()
        """,
        upstream = listOf(api, pay),
    )

    @Test
    fun `a mark exported by one module selects its implementation in another`() {
        for (stripped in listOf(false, true)) {
            val run = app(pay(stripped))
            assertEquals(emptyList(), run.errors, "stripped=$stripped\n${run.log}")
            assertTrue("mark = Stripe::class.java" in run.file("PayViewModel_Adapters.kt"), "stripped=$stripped")
        }
    }

    @Test
    fun `a user-facing registry carries no annotation name`() {
        val registry = ksp(
            "Pay.kt" to """
                package com.example.pay
                @Retention(AnnotationRetention.SOURCE) annotation class Stripe
                interface Gateway
                @Stripe class StripeGateway : Gateway
            """,
            module = ":pay",
            aggregate = false,
            options = mapOf("kite.stripProvenance" to "true"),
        ).file("Pay_BindingRegistry.kt")
        // R8 renames the class; a string would keep its original name in the APK.
        assertFalse("\"com.example.pay.Stripe\"" in registry, registry)
        assertTrue("Stripe::class" in registry, registry)
    }
}
