package com.kite.demo.data

import android.content.Context
import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Named
import com.kite.di.annotations.Singleton

/** Stand-in for a real HTTP client; provided by AppModule with construction logic. */
class HttpClient(val cache: RequestCache, val timeoutMillis: Long) {
    fun get(path: String): String = "response($path)"
}

/** Injects the built-in `Context` binding (the application) — no declaration needed. */
@Injectable
@Singleton
class RequestCache @Inject constructor(context: Context) {
    private val dir = context.cacheDir
    fun describe(): String = "cache@${dir.name}"
}

@Injectable
@Singleton
class ApiClient @Inject constructor(
    private val client: HttpClient,
    @Named("apiKey") private val apiKey: String,
) {
    fun fetchUser(): String = client.get("/user?key=$apiKey")
}
