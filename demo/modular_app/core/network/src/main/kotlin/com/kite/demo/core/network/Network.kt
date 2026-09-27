package com.kite.demo.core.network

import android.content.Context
import com.kite.di.runtime.Supplies
import java.util.concurrent.Executor

/** The module's public contract — downstream modules depend on this, never on [HttpApiClient]. */
interface ApiClient {
    fun fetch(path: String): String
    fun fetchUser(): String
}

/** Internal to the http stack: the transport the api client speaks through. */
interface HttpClient {
    fun get(path: String): String
}

interface RequestCache {
    fun describe(): String
}

/**
 * `java.util.concurrent.Executor` is a type the project does not own, so nothing
 * infers it — this class **supplies** it (ADR 13). Implementing `Supplies<T>` binds
 * `T`, so any module may now take an `Executor` constructor parameter while the pool
 * itself stays here, in the module that knows how it should be configured.
 *
 * Singleton by default (ADR 11), so there is one executor for the process without a
 * single annotation, and `Graph.start` gains nothing: the application never learns
 * that the transport dispatches at all.
 */
class HttpDispatcher : Supplies<Executor> {
    // Direct dispatch: a real pool would leave threads behind in the demo's tests.
    override val value: Executor = Executor { command -> command.run() }
}

/**
 * Stand-in for a real HTTP transport. `timeoutMillis` is a **graph argument**
 * (rule R5): nothing in the graph provides a `Long`, so it bubbles up to become
 * a parameter of the generated `Graph.start(…, timeoutMillis = …)` — construction
 * logic without a module. Note the leaf bubbles from the *implementation*: the
 * interface stays free of construction details.
 *
 * `dispatcher` bubbles nowhere, by contrast: [HttpDispatcher] supplies it.
 */
class DefaultHttpClient(
    val cache: RequestCache,
    val dispatcher: Executor,
    val timeoutMillis: Long,
) : HttpClient {
    override fun get(path: String): String = "response($path)"
}

/** Injects the built-in `Context` binding (the application) — no declaration needed. */
class FileRequestCache(context: Context) : RequestCache {
    private val dir = context.cacheDir
    override fun describe(): String = "cache@${dir.name}"
}

/**
 * Sole implementation of [ApiClient] — bound by R1, exported to downstream
 * modules without a single decision.
 */
class HttpApiClient(
    /** Standard-library Lazy over the *interface* — the transport is not built until the first request. */
    private val client: Lazy<HttpClient>,
    /** Graph argument: supplied once in `Graph.start(apiKey = …)`, keyed by the parameter name. */
    private val apiKey: String,
) : ApiClient {
    override fun fetch(path: String): String = client.value.get("$path?key=$apiKey")

    override fun fetchUser(): String = fetch("/user")
}
