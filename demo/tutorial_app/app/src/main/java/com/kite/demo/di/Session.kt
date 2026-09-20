package com.kite.demo.di

import com.kite.demo.core.analytics.Analytics

/** One cart per login session — consumers depend on this, never on the implementation. */
interface SessionCart {
    val items: List<String>
    fun add(item: String)
}

/**
 * A custom lifetime the built-ins don't cover: opened when a user logs in, closed
 * on logout (see `Kite.openScope` and the GUIDE's "Custom scopes" section).
 *
 * Cached in the "Session" scope and dropped on close. The lifetime is one line in
 * GraphRules.kt: `@Scoped(DefaultSessionCart::class, "Session", level = 10)` — the
 * rule names the *implementation*, because a lifetime belongs to the class that is
 * actually constructed (naming the interface is a build error with that wording).
 */
class DefaultSessionCart(
    private val analytics: Analytics,
) : SessionCart {

    private val entries = mutableListOf<String>()
    override val items: List<String> get() = entries

    override fun add(item: String) {
        entries += item
        analytics.track("cart_add")
    }
}
