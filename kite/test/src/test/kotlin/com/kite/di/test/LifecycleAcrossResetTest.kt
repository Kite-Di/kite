package com.kite.di.test

import android.app.Application
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.kite.di.runtime.Kite
import com.kite.di.runtime.KiteTestApi
import com.kite.di.runtime.KiteTesting
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertSame

/**
 * An Activity that outlives a discarded graph — a KiteTestRule not at `order = 0` —
 * keeps getting lifecycle callbacks registered for the old one (doc 14 §4).
 */
@OptIn(KiteTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleAcrossResetTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = KiteTesting.reset()

    @Test
    fun a_fragment_added_after_a_restart_resolves_against_the_app_scope() {
        KiteTesting.reset()
        Kite.init(app)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()

        KiteTesting.reset()
        Kite.init(app)
        val fragment = Fragment()
        // Used to throw: the fragment scope was opened under the old graph's activity scope.
        controller.get().supportFragmentManager.beginTransaction().add(fragment, "f").commitNow()

        assertSame(Kite.get<Counter>(), Kite.get<Counter>(owner = fragment))
        assertSame(Kite.get<Counter>(), Kite.get<Counter>(owner = controller.get()))
        controller.pause().stop().destroy()
    }

    @Test
    fun an_activity_living_through_a_gap_with_no_graph_opens_and_closes_nothing() {
        KiteTesting.reset()
        Kite.init(app)
        KiteTesting.reset()
        // No graph between the reset and the next start: the callbacks must not crash.
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction().add(Fragment(), "f").commitNow()
        controller.pause().stop().destroy()
    }
}
