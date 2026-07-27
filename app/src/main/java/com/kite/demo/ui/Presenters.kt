package com.kite.demo.ui

import com.kite.demo.data.UserRepository

/**
 * Survives configuration changes together with its activity scope
 * (graph.rules: `scope …SessionState -> activity`).
 */
class SessionState {
    var visits: Int = 0
}

/** Unscoped (the default for plain classes): a fresh instance per injection. */
class GreetingUseCase(
    private val repository: UserRepository,
) {
    fun greeting(): String = "Hello, ${repository.userName()}!"
}

/**
 * Resolved at runtime via `by injected()` — invisible to static inference, so it
 * is declared once in graph.rules: `root …FirstPresenter` (plus a fragment scope).
 */
class FirstPresenter(
    private val greeting: GreetingUseCase,
    private val session: SessionState,
) {
    fun headline(): String {
        session.visits++
        return "${greeting.greeting()} (visit #${session.visits} this activity)"
    }
}

class SecondPresenter(
    private val session: SessionState,
) {
    fun headline(): String = "Session visits so far: ${session.visits}"
}
