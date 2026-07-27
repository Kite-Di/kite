package com.kite.demo.data

interface UserRepository {
    fun userName(): String
}

/**
 * The sole implementation of [UserRepository] — inference rule R1 binds it to the
 * interface with no annotation, singleton by default. Analytics is injected
 * lazily — created only on first use (and visible as a dashed edge on the
 * dependency board).
 */
class NetworkUserRepository(
    private val api: ApiClient,
    private val analytics: Lazy<Analytics>,
    private val crashReporter: CrashReporter,
) : UserRepository {

    override fun userName(): String {
        analytics.value.track("user_fetched")
        return "Ada from ${api.fetchUser()}"
    }
}
