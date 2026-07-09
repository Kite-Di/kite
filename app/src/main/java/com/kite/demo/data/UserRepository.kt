package com.kite.demo.data

import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Singleton
import com.kite.di.runtime.Lazy

interface UserRepository {
    fun userName(): String
}

/**
 * Spring-style: one annotation, bound to its interface explicitly. Analytics is
 * injected lazily — created only on first use (and visible as a dashed edge on the
 * dependency board).
 */
@Injectable(bindTo = [UserRepository::class])
@Singleton
class NetworkUserRepository @Inject constructor(
    private val api: ApiClient,
    private val analytics: Lazy<Analytics>,
    private val crashReporter: CrashReporter,
) : UserRepository {

    override fun userName(): String {
        analytics.get().track("user_fetched")
        return "Ada from ${api.fetchUser()}"
    }
}
