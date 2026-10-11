package com.kite.di.processor.validate

import com.kite.di.processor.harness.ksp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long-lived binding must not reach a shorter-lived one — directly, through a set,
 * or through something unscoped that is built in the consumer's scope.
 */
class ScopeReachTest {

    private fun graph(code: String) = ksp("Graph.kt" to code)

    @Test
    fun `a singleton consuming a set with an activity-scoped element is a scope violation`() {
        val run = graph(
            """
            package com.example
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            interface Task
            class AppTask : Task
            class ScreenTask : Task
            class Runner(val tasks: Set<Task>)
            @Root(Runner::class)
            @Scoped(ScreenTask::class, "activity")
            private object GraphRules
            """,
        )
        assertTrue(run.errors.any { "Scope violation" in it && "Runner" in it && "ScreenTask" in it }, run.log)
    }

    @Test
    fun `a singleton reaching an activity binding through an unscoped one is a scope violation`() {
        val run = graph(
            """
            package com.example
            import com.kite.di.rules.Fresh
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            class Session
            @Fresh class Middle(val session: Session)
            class Holder(val middle: Middle)
            @Root(Holder::class)
            @Scoped(Session::class, "activity")
            private object GraphRules
            """,
        )
        assertTrue(run.errors.any { "Scope violation" in it && "Holder" in it && "Session" in it }, run.log)
    }

    @Test
    fun `an activity binding may consume a set of activity-scoped elements`() {
        val run = graph(
            """
            package com.example
            import com.kite.di.rules.Root
            import com.kite.di.rules.Scoped
            interface Task
            class ScreenTask : Task
            class Runner(val tasks: Set<Task>)
            @Root(Runner::class)
            @Scoped(ScreenTask::class, "activity")
            @Scoped(Runner::class, "activity")
            private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
    }

    @Test
    fun `a singleton may reach singletons through an unscoped binding`() {
        val run = graph(
            """
            package com.example
            import com.kite.di.rules.Fresh
            import com.kite.di.rules.Root
            class Clock
            @Fresh class Middle(val clock: Clock)
            class Holder(val middle: Middle)
            @Root(Holder::class)
            private object GraphRules
            """,
        )
        assertEquals(emptyList(), run.errors, run.log)
    }
}
