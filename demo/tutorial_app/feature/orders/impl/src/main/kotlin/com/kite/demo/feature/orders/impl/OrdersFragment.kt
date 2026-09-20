package com.kite.demo.feature.orders.impl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.kite.demo.core.designsystem.AppCard
import com.kite.demo.core.designsystem.AppTheme
import com.kite.demo.core.designsystem.ScreenColumn
import com.kite.demo.core.designsystem.SecondaryButton
import com.kite.demo.core.designsystem.SectionHeader
import com.kite.demo.core.designsystem.Spacing
import com.kite.demo.core.designsystem.StatusChip
import com.kite.demo.feature.orders.api.Order
import com.kite.demo.feature.orders.api.OrderStatus

/**
 * The orders screen: a Compose list hosted in a fragment, themed by
 * :core:designsystem, fed by the generated `rememberOrdersViewModel()` adapter.
 */
class OrdersFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AppTheme {
                OrdersScreen()
            }
        }
    }
}

@Composable
private fun OrdersScreen(viewModel: OrdersViewModel = rememberOrdersViewModel()) {
    val reordered by viewModel.reordered.collectAsState()

    ScreenColumn {
        SectionHeader("Recent orders")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            items(viewModel.orders, key = Order::id) { order ->
                OrderCard(
                    order = order,
                    reordered = order.id in reordered,
                    onReorder = { viewModel.onReorder(order) },
                )
            }
        }
    }
}

@Composable
private fun OrderCard(order: Order, reordered: Boolean, onReorder: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(order.title, style = MaterialTheme.typography.titleMedium)
            StatusChip(order.status.name.lowercase(), emphasized = order.status == OrderStatus.PROCESSING)
        }
        Text("Order #${order.id}", style = MaterialTheme.typography.bodyMedium)
        SecondaryButton(
            text = if (reordered) "Reordered ✓" else "Reorder",
            onClick = onReorder,
        )
    }
}
