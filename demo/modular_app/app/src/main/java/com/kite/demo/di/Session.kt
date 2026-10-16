package com.kite.demo.di

import com.kite.demo.core.analytics.Analytics

/** One cart per login session — consumers depend on this, never on the implementation. */
interface SessionCart {
    val items: List<String>
    fun add(item: String)
}

/**
 * A lifetime the built-ins don't cover: one cart per login. The whole declaration is
 * one line in GraphRules.kt — `@Scoped(DefaultSessionCart::class, "Session")` — and
 * the processor generates `openSession()` / `closeSession()` and the `sessionCart()`
 * accessor from it. The rule names the *implementation*, because a lifetime belongs
 * to the class that is actually constructed.
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
