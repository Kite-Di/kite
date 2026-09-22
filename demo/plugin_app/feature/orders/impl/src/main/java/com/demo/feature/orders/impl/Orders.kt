package com.demo.feature.orders.impl

import com.demo.core.network.HttpClient
import com.demo.core.plugin.DeepLinkHandler
import com.demo.core.plugin.SettingsEntry
import com.demo.core.plugin.StartupTask
import com.demo.feature.orders.api.OrdersRepository

class NetworkOrdersRepository(private val http: HttpClient) : OrdersRepository {
    override fun latest() = http.get("orders/latest")
}

/** Three contributions, no registration anywhere: implementing is registering. */
class SyncOrdersTask(private val repository: OrdersRepository) : StartupTask {
    override val name = "sync-orders"
    override fun run() { repository.latest() }
}

class OrdersDeepLink(private val repository: OrdersRepository) : DeepLinkHandler {
    override val host = "orders"
    override fun open(path: String) = "orders:" + repository.latest() + "/" + path
}

class OrdersSettings : SettingsEntry {
    override val title = "Orders"
    override val order = 20
}
