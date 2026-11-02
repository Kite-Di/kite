package com.kite.di.test

import android.app.Application
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.kite.di.runtime.Kite
import com.kite.di.runtime.KiteTestApi
import com.kite.di.runtime.KiteTesting
import com.kite.di.runtime.android.injected
import com.kite.di.runtime.injected
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertSame

/**
 * A lookup by type is an ordinary call: nothing to opt in to. The generated accessor
 * is the form the compiler checks; this one is checked when it runs. That this file
 * compiles is half the test.
 */
@OptIn(KiteTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookupByTypeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = KiteTesting.reset()

    @Test
    fun every_lookup_by_type_is_written_without_an_opt_in() {
        KiteTesting.reset()
        Kite.init(app)
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "f").commitNow()

        val counter = Kite.get<Counter>()
        val anywhere: Counter by injected()
        val inActivity: Counter by activity.injected()
        val inFragment: Counter by fragment.injected()
        val inView: Counter by View(activity).injected()
        val inContext: Counter by app.injected()
        val lookups = listOf(
            Kite.get(Counter::class), Kite.get(Counter::class.java),
            anywhere, inActivity, inFragment, inView, inContext,
        )
        for (lookup in lookups) assertSame(counter, lookup)
        controller.pause().stop().destroy()
    }
}
