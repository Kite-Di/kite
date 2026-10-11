package com.kite.di.processor.scan

import com.kite.di.processor.harness.ksp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which annotations act as marks: the project's own, and nothing a library brought. */
class MarksTest {

    @Test
    fun `a project annotation on both sides selects the implementation`() {
        val run = ksp(
            "Pay.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                @Retention(AnnotationRetention.SOURCE) annotation class Stripe
                @Retention(AnnotationRetention.SOURCE) annotation class PayPal
                interface Gateway
                @Stripe class StripeGateway : Gateway
                @PayPal class PayPalGateway : Gateway
                class CheckoutViewModel(@Stripe val gateway: Gateway) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertTrue("mark = Stripe::class.java" in run.file("CheckoutViewModel_Adapters.kt"))
    }

    @Test
    fun `a library annotation on an implementation is not a mark`() {
        val run = ksp(
            "Repo.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                interface Repo
                @kotlinx.serialization.Serializable class RealRepo : Repo
                class RepoViewModel(val repo: Repo) : ViewModel()
            """,
        )
        // Used to bind Repo only under the mark "Serializable" and report Repo unbound.
        assertEquals(emptyList(), run.errors, run.log)
        val registry = run.file("App_BindingRegistry.kt")
        assertTrue("extraKeys = listOf(Key(Repo::class.java))" in registry, registry)
        assertFalse("Serializable" in registry, registry)
    }

    @Test
    fun `a library annotation on a parameter is not a mark`() {
        val run = ksp(
            "Repo.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                interface Repo
                class RealRepo : Repo
                class RepoViewModel(@org.junit.Rule val repo: Repo) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertFalse("mark =" in run.file("RepoViewModel_Adapters.kt"))
    }

    @Test
    fun `the project annotation is the mark whatever else sits before it`() {
        val run = ksp(
            "Pay.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                @Retention(AnnotationRetention.SOURCE) annotation class Stripe
                interface Gateway
                @kotlinx.serialization.Serializable @Stripe class StripeGateway : Gateway
                class PlainGateway : Gateway
                class CheckoutViewModel(@Stripe val gateway: Gateway, val plain: Gateway) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        val adapter = run.file("CheckoutViewModel_Adapters.kt")
        assertTrue("mark = Stripe::class.java" in adapter, adapter)
    }
}
