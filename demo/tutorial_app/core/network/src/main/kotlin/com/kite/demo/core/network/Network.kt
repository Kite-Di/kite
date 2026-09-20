package com.kite.demo.core.network

import android.content.Context

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
 * Stand-in for a real HTTP transport. `timeoutMillis` is a **graph argument**
 * (rule R5): nothing in the graph provides a `Long`, so it bubbles up to become
 * a parameter of the generated `Graph.start(…, timeoutMillis = …)` — construction
 * logic without a module. Note the leaf bubbles from the *implementation*: the
 * interface stays free of construction details.
 */
class DefaultHttpClient(
    val cache: RequestCache,
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
