package com.kite.di.runtime.android

import android.app.Activity
import androidx.fragment.app.Fragment
import com.kite.di.runtime.Kite

/**
 * `private val presenter: MainPresenter by injected()` — resolves against this
 * Activity's scope (falling back up the scope tree) on first access.
 */
inline fun <reified T : Any> Activity.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = this) }

/** Fragment counterpart of [Activity.injected]; resolves against the fragment scope. */
inline fun <reified T : Any> Fragment.injected(qualifier: String? = null): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.get(T::class.java, qualifier, owner = this) }
