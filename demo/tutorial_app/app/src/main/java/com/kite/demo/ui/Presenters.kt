package com.kite.demo.ui

import com.kite.demo.feature.profile.api.UserRepository
import com.kite.di.rules.Fresh

/**
 * Plain mutable state, deliberately *not* behind an interface: it carries values,
 * not behaviour, so there is nothing to substitute. It joins the graph because the
 * presenters take it as a constructor parameter (rule R4).
 *
 * Survives configuration changes together with its activity scope
 * (GraphRules.kt: `@Scoped(SessionState::class, "activity")`).
 */
class SessionState {
    var visits: Int = 0
}

interface GreetingUseCase {
    fun greeting(): String
}

/**
 * The one lifetime decision that lives on the class (ADR 11): a new instance
 * every time it is injected, instead of the default shared singleton — cheap,
 * stateless glue that nobody should hold on to. `@Fresh` goes on the
 * implementation; the interface says nothing about lifetimes.
 */
@Fresh
class DefaultGreetingUseCase(
    private val repository: UserRepository,
) : GreetingUseCase {
    override fun greeting(): String = "Hello, ${repository.userName()}!"
}

interface FirstPresenter {
    fun headline(): String
}

interface SecondPresenter {
    fun headline(): String
}

/**
 * Resolved at runtime via `by injected()` in FirstFragment. Call sites in function
 * bodies are invisible to KSP, but implementing [FirstPresenter] already puts the
 * class in the graph (R1) — so no `@Root` decision is needed, only the lifetime:
 * `@Scoped(DefaultFirstPresenter::class, "fragment")`.
 */
class DefaultFirstPresenter(
    private val greeting: GreetingUseCase,
    private val session: SessionState,
) : FirstPresenter {
    override fun headline(): String {
        session.visits++
        return "${greeting.greeting()} (visit #${session.visits} this activity)"
    }
}

class DefaultSecondPresenter(
    private val session: SessionState,
) : SecondPresenter {
    override fun headline(): String = "Session visits so far: ${session.visits}"
}
