package com.kite.demo

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.kite.demo.data.Analytics
import com.kite.demo.ui.CounterViewModel
import com.kite.di.generated.Graph
import com.kite.di.runtime.Key
import com.kite.di.runtime.android.resolveViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * androidx ViewModel + Kite on the JVM: lifecycle-viewmodel's store/provider
 * are plain classes, so rotation (same store, new provider) and finish
 * (store.clear) can be simulated without a device. The create lambda below is
 * exactly what the generated `counterViewModel()` adapter contains.
 */
class ViewModelIntegrationTest {

    @Test
    fun `view model is Kite-built, store-retained across rotation, dropped on clear`() {
        // The generated startup façade: graph arguments supplied once, type-checked.
        Graph.start(Application(), apiKey = "test-key", timeoutMillis = 1)

        val store = ViewModelStore() // what the Activity retains across rotation
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }

        fun vm(): CounterViewModel = resolveViewModel(owner, null, CounterViewModel::class.java) { resolver, scope, _ ->
            CounterViewModel(analytics = resolver.resolve(Key(Analytics::class.java), scope))
        }

        val before = vm()
        before.onFabClick()

        // Rotation: a new Activity asks again, but the store survives.
        val after = vm()
        assertSame(before, after)
        assertEquals(1, after.clicks)

        // Real finish: the store clears, the next Activity gets a fresh ViewModel.
        store.clear()
        val fresh = vm()
        assertNotSame(before, fresh)
        assertEquals(0, fresh.clicks)
    }
}
