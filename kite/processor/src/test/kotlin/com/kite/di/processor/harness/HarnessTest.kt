package com.kite.di.processor.harness

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The harness itself: a complete graph generates, an incomplete one reports. */
class HarnessTest {

    @Test
    fun `an interface implementation reached from a ViewModel generates its factory and adapter`() {
        val run = ksp(
            "Repo.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                interface Repo
                class RealRepo : Repo
                class ScreenViewModel(val repo: Repo) : ViewModel()
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertTrue("RealRepo(" in run.file("RealRepo_Factory.kt"))
        assertTrue("Key(Repo::class.java)" in run.file("ScreenViewModel_Adapters.kt"))
    }

    @Test
    fun `a rule from GraphRules is read`() {
        val run = ksp(
            "Rules.kt" to """
                package com.example
                import com.kite.di.rules.Root
                class Tracker
                @Root(Tracker::class)
                private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
        assertTrue("Tracker(" in run.file("Tracker_Factory.kt"))
    }

    @Test
    fun `a dependency nothing can build is the processor's error`() {
        val run = ksp(
            "Screen.kt" to """
                package com.example
                import androidx.lifecycle.ViewModel
                class Repo private constructor()
                class ScreenViewModel(val repo: Repo) : ViewModel()
            """,
        )
        assertTrue(run.errors.any { "Repo" in it }, run.log)
    }
}
