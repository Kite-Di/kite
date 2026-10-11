package com.kite.di.processor.scan

import com.kite.di.processor.harness.ksp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a ViewModel pulls into the graph, and what its generated adapter resolves. */
class ViewModelInferenceTest {

    @Test
    fun `a class reached only through a ViewModel brings its own dependencies`() {
        val run = ksp(
            "Feed.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                class Cache
                class FeedLoader(val cache: Cache)
                class FeedViewModel(val loader: FeedLoader) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertTrue("FeedLoader(" in run.file("FeedLoader_Factory.kt"))
        assertTrue("Cache(" in run.file("Cache_Factory.kt"))
    }

    @Test
    fun `the closure keeps going however deep it is`() {
        val run = ksp(
            "Deep.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                class D
                class C(val d: D)
                class B(val c: C)
                class DeepViewModel(val b: B) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        for (name in listOf("B", "C", "D")) run.file("${name}_Factory.kt")
    }

    @Test
    fun `an optional ViewModel parameter nothing provides is left to its default`() {
        val run = ksp(
            "Feed.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                class Cache
                class FeedViewModel(val cache: Cache, val pageSize: Int = 20) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        val adapter = run.file("FeedViewModel_Adapters.kt")
        assertTrue("cache = " in adapter, adapter)
        // Resolving it would throw "No binding for kotlin.Int" on the device.
        assertFalse("pageSize" in adapter, adapter)
    }

    @Test
    fun `an optional ViewModel parameter something provides is injected`() {
        val run = ksp(
            "Feed.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                class Cache
                class FeedViewModel(val cache: Cache, val clock: Clock = Clock()) : ViewModel()
                interface Ticker
                class Clock : Ticker
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertTrue("clock = " in run.file("FeedViewModel_Adapters.kt"))
    }
}
