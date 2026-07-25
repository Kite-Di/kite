package com.kite.di.runtime.android

import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.kite.di.runtime.Kite

/**
 * Bridges Kite into androidx ViewModels: the ViewModelStore owns the
 * instance (rotation retention, `onCleared`), Kite builds it (constructor
 * dependencies come from the graph).
 *
 * ViewModels must be unscoped `@Injectable` classes — the store is the cache;
 * a scope annotation on a ViewModel subclass is a compile-time error.
 *
 * Public so it can plug into hand-rolled `ViewModelProvider` setups; day to day,
 * use `by injectedViewModel()` instead.
 */
class KiteViewModelFactory(
    /** Scope context the ViewModel's *dependencies* resolve against (app scope when null). */
    private val scopeOwner: Any? = null,
    private val qualifier: String? = null,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        Kite.get(modelClass, qualifier, scopeOwner)
}

/**
 * `private val counter: CounterViewModel by injectedViewModel()` — created by the
 * injector, retained by this Activity's ViewModelStore across rotation, cleared
 * when the Activity truly finishes.
 */
inline fun <reified VM : ViewModel> ComponentActivity.injectedViewModel(
    qualifier: String? = null,
): Lazy<VM> = lazy(LazyThreadSafetyMode.NONE) { kiteViewModel<VM>(this, this, qualifier) }

/** Fragment counterpart of [ComponentActivity.injectedViewModel] (fragment-owned store). */
inline fun <reified VM : ViewModel> Fragment.injectedViewModel(
    qualifier: String? = null,
): Lazy<VM> = lazy(LazyThreadSafetyMode.NONE) { kiteViewModel<VM>(this, this, qualifier) }

/** Store lookup keyed by type + qualifier, so qualified variants of one VM coexist. */
inline fun <reified VM : ViewModel> kiteViewModel(
    storeOwner: ViewModelStoreOwner,
    scopeOwner: Any?,
    qualifier: String?,
): VM {
    val provider = ViewModelProvider(storeOwner, KiteViewModelFactory(scopeOwner, qualifier))
    val key = if (qualifier == null) VM::class.java.name else "$qualifier@${VM::class.java.name}"
    return provider.get(key, VM::class.java)
}
