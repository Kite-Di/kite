package com.kite.demo.di

import com.kite.demo.data.Analytics

/**
 * A custom lifetime the built-ins don't cover: opened when a user logs in, closed
 * on logout (see `Kite.openScope` and the GUIDE's "Custom scopes" section).
 *
 * One cart per login session — cached in the "Session" scope and dropped on
 * close. The lifetime is one line in GraphRules.kt:
 * `@Scoped(SessionCart::class, "Session", level = 10)` (levels order lifetimes;
 * collisions with the built-ins 0/1/2 are a build error).
 */
class SessionCart(
    private val analytics: Analytics,
) {
    val items = mutableListOf<String>()

    fun add(item: String) {
        items += item
        analytics.track("cart_add")
    }
}
