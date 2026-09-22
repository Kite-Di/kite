package com.demo.core.network

import com.demo.core.plugin.StartupTask

interface HttpClient {
    fun get(path: String): String
}

class RealHttpClient(private val baseUrl: String) : HttpClient {
    override fun get(path: String) = "$baseUrl/$path"
}

/** Infrastructure contributes to startup exactly like a feature does. */
class WarmUpConnectionPoolTask(private val http: HttpClient) : StartupTask {
    override val name = "warm-up-pool"
    override val priority = 10
    override fun run() { http.get("ping") }
}
