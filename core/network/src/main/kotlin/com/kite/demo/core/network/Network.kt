package com.kite.demo.core.network

import android.content.Context

/**
 * Stand-in for a real HTTP client. `timeoutMillis` is a **graph argument** (rule
 * R5): nothing in the graph provides a `Long`, so it bubbles up to become a
 * parameter of the generated `Graph.start(…, timeoutMillis = …)` — construction
 * logic without a module.
 */
class HttpClient(val cache: RequestCache, val timeoutMillis: Long) {
    fun get(path: String): String = "response($path)"
}

/** Injects the built-in `Context` binding (the application) — no declaration needed. */
class RequestCache(context: Context) {
    private val dir = context.cacheDir
    fun describe(): String = "cache@${dir.name}"
}

class ApiClient(
    /** Standard-library Lazy — the HTTP stack is not built until the first request. */
    private val client: Lazy<HttpClient>,
    /** Graph argument: supplied once in `Graph.start(apiKey = …)`, keyed by the parameter name. */
    private val apiKey: String,
) {
    fun fetch(path: String): String = client.value.get("$path?key=$apiKey")

    fun fetchUser(): String = fetch("/user")
}
