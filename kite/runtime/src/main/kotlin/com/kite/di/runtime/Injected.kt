package com.kite.di.runtime

/**
 * `private val analytics: Analytics by injected()` — works in any class. Resolves
 * from the app scope on first access (thread-safe). Inside an Activity or Fragment
 * prefer the extensions in `com.kite.di.runtime.android`, which resolve
 * against the caller's own scope so @ActivityScoped/@FragmentScoped bindings work.
 */
inline fun <reified T : Any> injected(qualifier: String? = null): kotlin.Lazy<T> =
    lazy { Kite.get(T::class.java, qualifier) }
