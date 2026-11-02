package com.kite.demo

import android.widget.TextView
import com.kite.demo.ui.SessionState
import com.kite.demo.ui.counterViewModel
import com.kite.di.runtime.Kite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real Android lifecycle, on the JVM: Robolectric launches the actual
 * MainActivity — the manifest `App` runs the generated `Graph.start`, the nav
 * host creates FirstFragment, and AndroidScopes opens/retains/closes scopes
 * through genuine lifecycle callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidLifecycleTest {

    @Test
    fun `fragment renders through the inferred graph end to end`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        // FirstFragment's presenter chain: FirstPresenter → GreetingUseCase →
        // UserRepository → ApiClient → HttpClient(cache, timeoutMillis) — all inferred.
        val text = controller.get().findViewById<TextView>(R.id.textview_first).text.toString()
        assertTrue("unexpected headline: $text", text.startsWith("Hello, Ada"))
        assertTrue("fragment-scoped visit counter must have run: $text", "visit #1" in text)
    }

    @Test
    fun `activity scoped state survives rotation and dies with the activity`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val before: SessionState = Kite.get(owner = controller.get())
        before.visits = 41

        controller.recreate() // configuration change: same scope, same instance
        val after: SessionState = Kite.get(owner = controller.get())
        assertSame(before, after)
        // 41 retained + 1: the recreated FirstFragment rendered again and counted its visit.
        assertEquals(42, after.visits)

        controller.pause().stop().destroy() // real finish: scope closes
        val next = Robolectric.buildActivity(MainActivity::class.java).setup()
        val fresh: SessionState = Kite.get(owner = next.get())
        assertNotSame(before, fresh)
        // a brand-new session: exactly one visit, from the new activity's fragment
        assertEquals(1, fresh.visits)
    }

    @Test
    fun `generated view model adapter retains the instance across rotation`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val before = with(controller.get()) { counterViewModel().value }
        before.onFabClick()

        controller.recreate()
        val after = with(controller.get()) { counterViewModel().value }
        assertSame(before, after)
        assertEquals(1, after.clicks)
    }
}
