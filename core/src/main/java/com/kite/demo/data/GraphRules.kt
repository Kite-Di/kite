package com.kite.demo.data

import com.kite.di.rules.Root

/**
 * This module's decisions — the things :core's code can't express:
 *
 *  - PayloadDecoder is consumed only by downstream modules. Per-module inference
 *    can't see those consumers, so exporting it is a decision: a `@Root` also
 *    makes a plain class part of the module's exported graph (its interface
 *    implementations and their closure export automatically).
 *
 * Lifetimes need no decisions here: everything is a singleton by default
 * (ADR 11) — which is exactly what a data layer wants.
 *
 * Decisions live with the module that owns the class — the app module cannot
 * scope or export :core's classes.
 */
@Root(PayloadDecoder::class)
private object GraphRules
