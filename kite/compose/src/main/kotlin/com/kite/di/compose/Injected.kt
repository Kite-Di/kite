package com.kite.di.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.kite.di.runtime.Kite
import com.kite.di.runtime.Resolver
import com.kite.di.runtime.ScopeNode
import com.kite.di.runtime.android.resolveViewModel

/**
 * `val analytics = injected<Analytics>()` — resolves against the host Activity's
 * scope (the app scope when the composition is not inside an Activity, e.g.
 * previews), remembered for the composition.
 */
@Composable
inline fun <reified T : Any> injected(qualifier: String? = null): T {
    val context = LocalContext.current
    return remember(context, qualifier) {
        Kite.get(T::class.java, qualifier, owner = context.findActivity())
    }
}

/**
 * Plumbing behind the generated `remember<Vm>()` composable adapters. The nearest
 * [LocalViewModelStoreOwner] (NavBackStackEntry, Fragment, Activity) owns the
 * instance; the generated [create] lambda builds it from the graph, resolved
 * against the host Activity's scope.
 */
@Composable
fun <VM : ViewModel> injectedViewModel(
    modelClass: Class<VM>,
    create: (resolver: Resolver, scope: ScopeNode, extras: CreationExtras) -> VM,
): VM {
    val storeOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "injectedViewModel requires a ViewModelStoreOwner in the composition"
    }
    val scopeOwner = LocalContext.current.findActivity()
    return resolveViewModel(storeOwner, scopeOwner, modelClass, create)
}

/** Unwraps ContextWrapper layers (themed/tinted contexts) down to the owning Activity. */
@PublishedApi
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
