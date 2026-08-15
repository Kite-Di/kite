package com.kite.demo.core.analytics

import com.kite.di.rules.Root

/**
 * This module's decisions — the things :core:analytics's code can't
 * express: both classes are consumed only by downstream modules (:core:network's
 * XmlParser, the feature impls, :app). Per-module inference can't see those
 * consumers, so exporting them is a decision — a `@Root` also makes a plain
 * class part of the module's exported graph.
 *
 * Lifetimes need no decisions: everything is a singleton by default (ADR 11).
 */
@Root(Analytics::class)
@Root(CrashReporter::class)
private object GraphRules
