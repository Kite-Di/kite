package com.kite.di.processor.codegen

import com.kite.di.processor.harness.compileErrors
import com.kite.di.processor.harness.ksp
import com.kite.di.processor.harness.upstream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The application composes a `Set<I>` whose elements live in other modules. */
class CrossModuleSetTest {

    private val api = upstream(
        ":api",
        "Api.kt" to """
            package com.example.api
            interface StartupTask
        """,
    )

    private val feature = upstream(
        ":feature",
        "Tasks.kt" to """
            package com.example.feature
            import com.example.api.StartupTask
            internal class SyncTask : StartupTask
            class WarmUpTask : StartupTask
        """,
        upstream = listOf(api),
    )

    private val app = arrayOf(
        "App.kt" to """
            package com.example.app
            import androidx.lifecycle.ViewModel
            import com.example.api.StartupTask
            class StartupViewModel(val tasks: Set<StartupTask>) : ViewModel()
        """,
    )

    @Test
    fun `an internal element of another module is named through its public key handle`() {
        val run = ksp(*app, upstream = listOf(api, feature))
        assertEquals(emptyList(), run.errors, run.log)
        val registry = run.file("App_BindingRegistry.kt")
        // `SyncTask::class` does not compile outside :feature — Kotlin's internal is a real wall.
        assertTrue("SyncTask_Key.KEY" in registry, registry)
        assertFalse("SyncTask::class" in registry, registry)
        assertEquals(emptyList(), compileErrors("App_BindingRegistry.kt", registry, listOf(api, feature)), registry)
    }

    @Test
    fun `a set the application consumes collects contributors from other modules`() {
        // Used to fail with "Set<StartupTask> … has no implementations": the check ran
        // before the classpath's contributors were merged in.
        val run = ksp(*app, upstream = listOf(api, feature))
        assertEquals(emptyList(), run.errors, run.log)
        val registry = run.file("App_BindingRegistry.kt")
        assertTrue("SetFactory(" in registry, registry)
    }

    @Test
    fun `a set nothing anywhere contributes to is still an error`() {
        val run = ksp(*app, upstream = listOf(api))
        assertTrue(run.errors.any { "Set<StartupTask>" in it && "has no implementations" in it }, run.log)
    }

    @Test
    fun `a public element of another module is named directly`() {
        val registry = ksp(*app, upstream = listOf(api, feature)).file("App_BindingRegistry.kt")
        assertTrue("Key(WarmUpTask::class.java)" in registry, registry)
    }
}
