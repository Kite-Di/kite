package com.kite.demo.feature.orders.impl

import com.kite.demo.core.network.ApiClient
import com.kite.demo.core.network.PayloadDecoder
import com.kite.demo.feature.orders.api.Order
import com.kite.demo.feature.orders.api.OrderStatus
import com.kite.demo.feature.orders.api.OrdersRepository

/**
 * Sole implementation of [OrdersRepository] — bound by inference rule R1, no
 * annotation. Both dependencies are cross-module edges into :core:network, and
 * both are interfaces: R1 bound them to their implementations there and exported
 * them on the registry, so this module resolves them with no decision on either
 * side of the boundary.
 */
class NetworkOrdersRepository(
    private val api: ApiClient,
    private val decoder: PayloadDecoder,
) : OrdersRepository {

    override fun orders(): List<Order> {
        // Stand-in for real parsing: the fake backend answers, the decoder picks
        // its strategy by format, and the "decoded" orders are fixed demo data.
        decoder.decode("json", api.fetch("/orders"))
        return listOf(
            Order("1042", "Espresso machine", OrderStatus.DELIVERED),
            Order("1043", "Grinder burrs, 64 mm", OrderStatus.SHIPPED),
            Order("1044", "Single-origin beans, 1 kg", OrderStatus.PROCESSING),
        )
    }
}
