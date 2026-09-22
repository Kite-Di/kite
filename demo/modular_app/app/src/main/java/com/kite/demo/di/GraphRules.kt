package com.kite.demo.di

import com.kite.demo.ui.DefaultFirstPresenter
import com.kite.demo.ui.DefaultSecondPresenter
import com.kite.demo.ui.SessionState
import com.kite.di.rules.Scoped

/**
 * The module's graph decisions — the few facts the code can't
 * express. Everything else is inferred from declarations. Compile-checked class
 * references; SOURCE retention, so nothing here reaches the APK. The dependency
 * board writes `@Bind` lines into this file when a decision card is clicked.
 *
 * Note what is *not* here any more: once every service sits behind an interface,
 * `@Root` decisions disappear — implementing a project interface already puts a
 * class in the graph and exports it to downstream modules, including classes that
 * are only ever resolved at runtime (the presenters, [AppInitializer]).
 * :core:network and :core:analytics have no GraphRules.kt at all for that reason.
 *
 * What remains are lifetimes, and they always name the *implementation*: a scope
 * is a property of the class that is constructed, not of the contract.
 */
@Scoped(SessionState::class, "activity") //  survives rotation, dies with the activity
@Scoped(DefaultFirstPresenter::class, "fragment") // one per fragment, cleared with it
@Scoped(DefaultSecondPresenter::class, "fragment")

// A custom lifetime: opened at login via Kite.openScope, closed on logout.
@Scoped(DefaultSessionCart::class, "Session", level = 10)
private object GraphRules
