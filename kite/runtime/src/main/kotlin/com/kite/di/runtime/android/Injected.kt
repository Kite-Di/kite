package com.kite.di.runtime.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.fragment.app.Fragment
import com.kite.di.runtime.Kite

/**
 * `private val presenter: MainPresenter by injected()` — resolves against this
 * Activity's scope (falling back up the scope tree) on first access.
 *
 * Not synchronized ([LazyThreadSafetyMode.NONE]): first access is expected on the
 * main thread, like the rest of the Activity. For off-main-thread first access use
 * the top-level `injected()` or [Kite.get] directly.
 */
inline fun <reified T : Any> Activity.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = this) }

/** Fragment counterpart of [Activity.injected]; resolves against the fragment scope. */
inline fun <reified T : Any> Fragment.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = this) }

/**
 * Custom-View counterpart: resolves against the hosting Activity's scope (so
 * activity-scoped bindings work), or the app scope when the view's context is not
 * an Activity (application-context inflation, previews).
 */
inline fun <reified T : Any> View.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = context.hostActivity()) }

/**
 * Everything-else counterpart — Services, BroadcastReceivers (via the received
 * context), custom framework classes. Resolves against the hosting Activity's
 * scope when the context wraps one, the app scope otherwise.
 */
inline fun <reified T : Any> Context.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = hostActivity()) }

/** Unwraps ContextWrapper layers (themed/tinted contexts) down to the owning Activity. */
@PublishedApi
internal fun Context.hostActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
