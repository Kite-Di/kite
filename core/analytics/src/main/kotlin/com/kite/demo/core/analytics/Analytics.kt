package com.kite.demo.core.analytics

import android.util.Log

/**
 * A plain class — no annotations. It joins the graph because other classes take
 * it as a constructor parameter (inference rule R4); singleton by default
 * (ADR 11). Its consumers live in *other* modules (:core:network, the feature
 * impls, :app), which per-module inference can't see — so exporting it is a
 * decision: `@Root(Analytics::class)` in this module's GraphRules.kt.
 */
class Analytics {
    fun track(event: String) {
        Log.d("Analytics", "event: $event")
    }
}
