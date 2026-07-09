package com.kite.demo.ui

import com.kite.demo.data.UserRepository
import com.kite.di.annotations.ActivityScoped
import com.kite.di.annotations.FragmentScoped
import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable

/** Survives configuration changes together with its activity scope. */
@Injectable
@ActivityScoped
class SessionState {
    var visits: Int = 0
}

/** Unscoped: a fresh instance per injection. */
@Injectable
class GreetingUseCase @Inject constructor(
    private val repository: UserRepository,
) {
    fun greeting(): String = "Hello, ${repository.userName()}!"
}

@Injectable
@FragmentScoped
class FirstPresenter @Inject constructor(
    private val greeting: GreetingUseCase,
    private val session: SessionState,
) {
    fun headline(): String {
        session.visits++
        return "${greeting.greeting()} (visit #${session.visits} this activity)"
    }
}

@Injectable
@FragmentScoped
class SecondPresenter @Inject constructor(
    private val session: SessionState,
) {
    fun headline(): String = "Session visits so far: ${session.visits}"
}
