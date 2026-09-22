package com.demo.feature.profile.impl

import com.demo.core.network.HttpClient
import com.demo.core.plugin.DeepLinkHandler
import com.demo.core.plugin.SettingsEntry
import com.demo.core.plugin.StartupTask
import com.demo.feature.profile.api.UserRepository

class NetworkUserRepository(private val http: HttpClient) : UserRepository {
    override fun name() = http.get("me")
}

class PrefetchProfileTask(private val repository: UserRepository) : StartupTask {
    override val name = "prefetch-profile"
    override val priority = 50
    override fun run() { repository.name() }
}

class ProfileDeepLink(private val repository: UserRepository) : DeepLinkHandler {
    override val host = "profile"
    override fun open(path: String) = "profile:" + repository.name()
}

class ProfileSettings : SettingsEntry {
    override val title = "Profile"
    override val order = 10
}
