package com.kite.demo

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.kite.demo.ui.CounterViewModel
import com.kite.di.runtime.Kite
import com.kite.di.runtime.android.KiteViewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * androidx ViewModel + Kite on the JVM: lifecycle-viewmodel's store/provider
 * are plain classes, so rotation (same store, new provider) and finish
 * (store.clear) can be simulated without a device.
 */
class ViewModelIntegrationTest {

    @Test
    fun `view model is Kite-built, store-retained across rotation, dropped on clear`() {
        Kite.init(Application())
        val store = ViewModelStore() // what the Activity retains across rotation

        val before = ViewModelProvider(store, KiteViewModelFactory())[CounterViewModel::class.java]
        before.onFabClick()

        // Rotation: a new Activity builds a new provider, but the store survives.
        val after = ViewModelProvider(store, KiteViewModelFactory())[CounterViewModel::class.java]
        assertSame(before, after)
        assertEquals(1, after.clicks)

        // Real finish: the store clears, the next Activity gets a fresh ViewModel.
        store.clear()
        val fresh = ViewModelProvider(store, KiteViewModelFactory())[CounterViewModel::class.java]
        assertNotSame(before, fresh)
        assertEquals(0, fresh.clicks)
    }
}
