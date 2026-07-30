package com.kite.demo.data

import android.util.Log

/**
 * A plain class — no annotations. It joins the graph because other classes take
 * it as a constructor parameter (inference rule R4); its singleton lifetime is one
 * `@Scoped(Analytics::class, "singleton")` decision in GraphRules.kt.
 */
class Analytics {
    fun track(event: String) {
        Log.d("Analytics", "event: $event")
    }
}
