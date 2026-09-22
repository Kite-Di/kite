package com.kite.demo.core.analytics

import android.util.Log

/**
 * The module's public contract. Downstream modules (:core:network, the feature
 * impls, :app) depend on this interface; the implementation below never leaves
 * this module.
 */
interface Analytics {
    fun track(event: String)
}

/**
 * Implementing a project interface *is* the binding (inference rule R1) — no
 * annotation anywhere — and it also makes the class part of this module's
 * exported graph: downstream modules resolve [Analytics] and get whichever
 * implementation this module decided on. Singleton by default (ADR 11).
 */
class LogcatAnalytics : Analytics {
    override fun track(event: String) {
        Log.d("Analytics", "event: $event")
    }
}

/**
 * The second implementation — and with it the one question the code cannot
 * answer: two classes now claim [Analytics], so inference stops and asks. The
 * answer is the `@Bind` line in this module's GraphRules.kt.
 *
 * (It "sends" to a backend the same way the rest of the demo does: by logging.)
 */
class NetworkAnalytics : Analytics {
    override fun track(event: String) {
        Log.d("Analytics", "POST /events $event")
    }
}
