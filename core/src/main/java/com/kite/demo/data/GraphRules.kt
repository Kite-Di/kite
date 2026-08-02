package com.kite.demo.data

import com.kite.di.rules.Root
import com.kite.di.rules.Scoped

/**
 * This module's decisions — the things :core's code can't express:
 *
 *  - Analytics is shared app-wide; plain classes default to unscoped, so the
 *    singleton lifetime is a decision.
 *  - PayloadDecoder is consumed only by downstream modules. Per-module inference
 *    can't see those consumers, so exporting it is a decision: a `@Root` also
 *    makes a plain class part of the module's exported graph (its interface
 *    implementations and their closure export automatically).
 *
 * Decisions live with the module that owns the class — the app module cannot
 * scope or export :core's classes.
 */
@Root(PayloadDecoder::class)
@Scoped(Analytics::class, "singleton")
private object GraphRules
