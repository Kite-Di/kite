package com.kite.di.runtime.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import com.kite.di.runtime.Kite
import com.kite.di.runtime.Key
import com.kite.di.runtime.Resolver
import com.kite.di.runtime.ScopeNode
import com.kite.di.runtime.observe.GraphEvent
import com.kite.di.runtime.observe.GraphEvents

/**
 * Plumbing behind the generated per-ViewModel adapters
 * (`private val counter by counterViewModel()`).
 *
 * The androidx ViewModelStore owns the instance — rotation retention and
 * `onCleared` — while [create] (generated code) builds it: constructor
 * dependencies resolved from the graph against the caller's scope, runtime
 * arguments captured from the adapter call, `SavedStateHandle` taken from
 * [CreationExtras]. [create] runs once per store lifetime; rotation returns the
 * retained instance without re-running it.
 */
fun <VM : ViewModel> resolveViewModel(
    storeOwner: ViewModelStoreOwner,
    scopeOwner: Any?,
    modelClass: Class<VM>,
    create: (resolver: Resolver, scope: ScopeNode, extras: CreationExtras) -> VM,
): VM {
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(requested: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return create(Kite.requireContainer(), Kite.scopeOf(scopeOwner), extras) as T
        }
    }
    // Every adapter call, not just first creation: the inspector learns "this
    // screen uses this ViewModel" even when the store returns a retained instance.
    if (GraphEvents.tracing) GraphEvents.emit(GraphEvent.ViewModelResolved(Key(modelClass), storeOwner.javaClass))
    return ViewModelProvider(storeOwner, factory)[modelClass]
}
