package com.kite.demo.core.analytics

import com.kite.di.rules.Bind

/**
 * This module's only decision. [Analytics] has two implementations,
 * and nothing in the code says which one the app runs on — so inference stops and
 * asks instead of guessing, and the answer is written down here, next to the
 * classes it chooses between.
 *
 * The build error came with this exact line ready to paste; on the dependency
 * board the same ambiguity shows up as a decision card with a button per
 * candidate. Swap `to = NetworkAnalytics::class` and every consumer in every
 * module switches — nothing else in the project mentions an implementation.
 *
 * Decisions live with the module that owns the implementations: putting this
 * `@Bind` in :app's GraphRules.kt is a build error that says so.
 */
@Bind(Analytics::class, to = LogcatAnalytics::class)
private object GraphRules
