package com.kite.demo.di

import com.kite.demo.ui.FirstPresenter
import com.kite.demo.ui.SecondPresenter
import com.kite.demo.ui.SessionState
import com.kite.di.rules.Root
import com.kite.di.rules.Scoped

/**
 * The module's graph decisions — the few facts the code can't
 * express. Everything else is inferred from declarations. Compile-checked class
 * references; SOURCE retention, so nothing here reaches the APK. The dependency
 * board writes `@Bind` lines into this file when a decision card is clicked.
 */

// Classes resolved only at runtime (`by injected()` / `Kite.get`) are
// invisible to static inference — declared as roots.
@Root(AppInitializer::class)
@Root(SessionCart::class)
@Root(FirstPresenter::class)
@Root(SecondPresenter::class)

// Lifetimes beyond the default (everything is a singleton unless decided
// otherwise — ADR 11; the opposite decision, @Fresh, sits on the class itself,
// see GreetingUseCase). Built-in scope names — "activity" | "fragment" | "none"
// — carry their own level. (Other modules' classes are decided in their own
// GraphRules.kt — :core:network's, :core:analytics's — decisions live with the
// module that owns the class.)
@Scoped(SessionState::class, "activity") //  survives rotation, dies with the activity
@Scoped(FirstPresenter::class, "fragment") // one per fragment, cleared with it
@Scoped(SecondPresenter::class, "fragment")

// A custom lifetime: opened at login via Kite.openScope, closed on logout.
@Scoped(SessionCart::class, "Session", level = 10)
private object GraphRules
