package com.kite.di.test

import android.app.Application
import com.kite.di.runtime.Kite
import com.kite.di.runtime.KiteException
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

interface Counter { var count: Int }
class RealCounter : Counter { override var count = 0 }
class FakeCounter : Counter { override var count = 100 }
class Screen(val counter: Counter)

@Retention(AnnotationRetention.SOURCE) annotation class Special
class SpecialCounter : Counter { override var count = 1 }
class SpecialScreen(@Special val counter: Counter)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KiteTestRuleTest {

    @get:Rule
    val kite = KiteTestRule { app -> Kite.init(app) }

    @Test
    fun a_state_written_in_one_test() {
        Kite.get<Counter>().count = 7
        previous = Kite.get<Counter>()
    }

    @Test
    fun b_is_gone_in_the_next() {
        assertNotSame(previous, Kite.get<Counter>())
        assertEquals(0, Kite.get<Counter>().count)
    }

    companion object {
        var previous: Any? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplaceTest {

    private val fake = FakeCounter()

    @get:Rule
    val kite = KiteTestRule { app ->
        replace<Counter>(fake)
        Kite.init(app)
    }

    @Test
    fun consumers_get_the_replacement() {
        assertSame(fake, Kite.get<Counter>())
        assertSame(fake, Kite.get<Screen>().counter)
    }

    @Test
    fun the_marked_binding_is_left_alone() {
        assertTrue(Kite.get<SpecialScreen>().counter is SpecialCounter)
    }

    @Test
    fun the_implementation_class_still_resolves_the_real_one() {
        assertTrue(Kite.get<RealCounter>() is RealCounter)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleMisuseTest {

    private val noop = object : Statement() {
        override fun evaluate() = Unit
    }

    private fun run(rule: KiteTestRule) = rule.apply(noop, Description.EMPTY).evaluate()

    @Test
    fun a_block_that_does_not_start_the_graph_fails() {
        val e = assertFailsWith<KiteException> { run(KiteTestRule { }) }
        assertTrue(e.message!!.startsWith("KiteTestRule: the block did not start the graph."), e.message)
    }

    @Test
    fun replace_after_start_fails() {
        val e = assertFailsWith<KiteException> {
            run(KiteTestRule { app ->
                Kite.init(app)
                replace<Counter>(FakeCounter())
            })
        }
        assertTrue(e.message!!.startsWith("replace<com.kite.di.test.Counter> after Graph.start."), e.message)
    }

    @Test
    fun replacing_what_nothing_provides_fails_the_start() {
        val e = assertFailsWith<KiteException> {
            run(KiteTestRule { app ->
                replace<FakeCounter>(FakeCounter())
                Kite.init(app)
            })
        }
        assertTrue("nothing in the graph provides it" in e.message!!, e.message)
    }

    @Test
    fun a_failed_start_consumes_its_replacements_and_leaves_no_graph() {
        run(KiteTestRule { app ->
            replace<FakeCounter>(FakeCounter())
            assertFailsWith<KiteException> { Kite.init(app) }
            // The bad replacement is gone and nothing half-built is running: a plain start works.
            Kite.init(app)
            assertTrue(Kite.get<Counter>() is RealCounter)
        })
    }

    @Test
    fun starting_twice_for_one_application_fails() {
        val e = assertFailsWith<KiteException> {
            run(KiteTestRule { app ->
                Kite.init(app)
                Kite.init(app)
            })
        }
        assertTrue(e.message!!.startsWith("Kite is already started for this Application."), e.message)
    }

    @Test
    fun a_new_application_gets_a_new_graph() {
        run(KiteTestRule { app ->
            Kite.init(app)
            val before = Kite.get<Counter>()
            Kite.init(Application())
            assertNotSame(before, Kite.get<Counter>())
        })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplaceMarkedTest {

    private val fake = FakeCounter()

    @get:Rule
    val kite = KiteTestRule { app ->
        replace<Counter>(fake, mark = Special::class)
        Kite.init(app)
    }

    @Test
    fun only_the_marked_key_moves() {
        assertSame(fake, Kite.get<SpecialScreen>().counter)
        assertTrue(Kite.get<Counter>() is RealCounter, "the unmarked binding keeps its implementation")
    }
}
