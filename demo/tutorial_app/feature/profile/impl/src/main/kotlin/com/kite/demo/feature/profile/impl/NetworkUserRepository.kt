package com.kite.demo.feature.profile.impl

import com.kite.demo.core.analytics.Analytics
import com.kite.demo.core.analytics.CrashReporter
import com.kite.demo.core.network.ApiClient
import com.kite.demo.feature.profile.api.UserRepository

/**
 * The sole implementation of [UserRepository] — inference rule R1 binds it to
 * the interface with no annotation, singleton by default, and exports the
 * binding for downstream modules (the interface lives in :feature:profile:api,
 * the consumers in :app). Every constructor dependency here is a cross-module
 * edge into :core:network / :core:analytics. Analytics is injected lazily —
 * created only on first use (a dashed edge on the dependency board).
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
