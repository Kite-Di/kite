package com.kite.demo.feature.orders.api

/**
 * A model, not a binding: data classes never join the graph (they carry values,
 * not behaviour) — they just travel across the :api boundary.
 */
data class Order(
    val id: String,
    val title: String,
    val status: OrderStatus,
)

enum class OrderStatus { PROCESSING, SHIPPED, DELIVERED }

/**
 * The orders feature's public contract. Downstream modules see only this; the
 * implementation and its network dependencies stay behind :feature:orders:impl.
 */
interface OrdersRepository {
    fun orders(): List<Order>
}
