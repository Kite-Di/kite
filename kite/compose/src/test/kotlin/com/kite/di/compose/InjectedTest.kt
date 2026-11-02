@file:OptIn(KiteGeneratedApi::class, KiteTestApi::class) // starts the graph by hand

package com.kite.di.compose

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.kite.di.runtime.Kite
import com.kite.di.runtime.KiteGeneratedApi
import com.kite.di.runtime.KiteTestApi
import com.kite.di.runtime.KiteTesting
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class Tracker
class ScreenState

/**
 * `injected<T>()` in a composition: an ordinary call, nothing to opt in to, resolved
 * against the Activity hosting the composition. That this file compiles is half the
 * test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InjectedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() = KiteTesting.reset()

    @Test
    fun injected_is_written_without_an_opt_in_and_resolves_in_the_host_activity() {
        KiteTesting.reset()
        Kite.init(app)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        var tracker: Tracker? = null
        var state: ScreenState? = null
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    tracker = injected<Tracker>()
                    // Activity-scoped: resolvable only through the host Activity.
                    state = injected<ScreenState>()
                }
            },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(Kite.get<Tracker>(), assertNotNull(tracker))
        assertSame(Kite.get<ScreenState>(owner = activity), assertNotNull(state))
        controller.pause().stop().destroy()
    }
}
