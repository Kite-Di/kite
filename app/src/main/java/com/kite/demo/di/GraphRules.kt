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

// Lifetimes beyond the defaults (interface implementations are singletons,
// everything else a fresh instance per injection). Built-in scope names —
// "singleton" | "activity" | "fragment" | "none" — carry their own level.
// (:core's classes are scoped in :core's own GraphRules.kt — decisions live
// with the module that owns the class.)
@Scoped(SessionState::class, "activity") //  survives rotation, dies with the activity
@Scoped(FirstPresenter::class, "fragment") // one per fragment, cleared with it
@Scoped(SecondPresenter::class, "fragment")

// A custom lifetime: opened at login via Kite.openScope, closed on logout.
@Scoped(SessionCart::class, "Session", level = 10)
private object GraphRules
