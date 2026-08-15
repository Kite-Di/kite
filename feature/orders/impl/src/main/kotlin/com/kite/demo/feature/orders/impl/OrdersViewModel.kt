package com.kite.demo.feature.orders.impl

import androidx.lifecycle.ViewModel
import com.kite.demo.core.analytics.Analytics
import com.kite.demo.feature.orders.api.Order
import com.kite.demo.feature.orders.api.OrdersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An androidx ViewModel — an entry point (inference rule R2), consuming the
 * feature's own R1 binding through its :api interface. Generated adapters:
 * `ordersViewModel()` / `rememberOrdersViewModel()`.
 */
class OrdersViewModel(
    repository: OrdersRepository,
    private val analytics: Analytics,
) : ViewModel() {

    val orders: List<Order> = repository.orders()

    private val _reordered = MutableStateFlow(emptySet<String>())
    val reordered: StateFlow<Set<String>> = _reordered.asStateFlow()

    fun onReorder(order: Order) {
        _reordered.value += order.id
        analytics.track("order_reordered:${order.id}")
    }
}
