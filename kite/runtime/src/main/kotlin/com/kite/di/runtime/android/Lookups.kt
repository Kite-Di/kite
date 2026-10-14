package com.kite.di.runtime.android

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.fragment.app.Fragment
import com.kite.di.runtime.Key
import com.kite.di.runtime.Kite
import com.kite.di.runtime.KiteGeneratedApi

/*
 * What generated accessors (`by analytics()`) call. Each resolves against its
 * receiver's own scope on first access, exactly as the `injected()` family does —
 * but only the processor calls them, and it generates an accessor only where the key
 * has a binding and the receiver has the binding's scope.
 *
 * Not synchronized ([LazyThreadSafetyMode.NONE]): first access is expected on the
 * main thread, like the rest of the component.
 */

/** Any Context — an Activity, a Service, the Application: resolves in the host Activity's scope, if any. */
@KiteGeneratedApi
fun <T : Any> Context.kiteLookup(key: Key): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.resolve(key, owner = hostActivity()) }

@KiteGeneratedApi
fun <T : Any> Activity.kiteLookup(key: Key): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.resolve(key, owner = this) }

@KiteGeneratedApi
fun <T : Any> Fragment.kiteLookup(key: Key): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.resolve(key, owner = this) }

@KiteGeneratedApi
fun <T : Any> View.kiteLookup(key: Key): Lazy<T> =
    lazy(LazyThreadSafetyMode.NONE) { Kite.resolve(key, owner = context.hostActivity()) }
