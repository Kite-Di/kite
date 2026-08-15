package com.kite.demo.core.network

import com.kite.di.rules.Root

/**
 * This module's decisions — the things :core:network's code can't
 * express: ApiClient and PayloadDecoder are consumed only by downstream modules
 * (the feature impls, :app's startup tasks). Per-module inference can't see
 * those consumers, so exporting them is a decision: a `@Root` also makes a
 * plain class part of the module's exported graph. Their closure (HttpClient,
 * RequestCache, the parsers) exports automatically.
 *
 * Lifetimes need no decisions here: everything is a singleton by default
 * (ADR 11) — which is exactly what a network stack wants.
 */
@Root(ApiClient::class)
@Root(PayloadDecoder::class)
private object GraphRules
