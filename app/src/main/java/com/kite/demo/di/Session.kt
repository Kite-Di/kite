package com.kite.demo.di

import com.kite.demo.data.Analytics
import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Scope

/**
 * A custom lifetime the built-ins don't cover: opened when a user logs in, closed
 * on logout (see `Kite.openScope` and the GUIDE's "Custom scopes" section).
 * The level is distinct from the built-ins (0/1/2) — collisions are a build error.
 */
@Scope(level = 10)
annotation class SessionScoped

/** One cart per login session — cached in the session scope, dropped on close. */
@Injectable
@SessionScoped
class SessionCart @Inject constructor(
    private val analytics: Analytics,
) {
    val items = mutableListOf<String>()

    fun add(item: String) {
        items += item
        analytics.track("cart_add")
    }
}
