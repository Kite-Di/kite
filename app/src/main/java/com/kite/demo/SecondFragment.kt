package com.kite.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.kite.demo.data.Analytics
import com.kite.demo.ui.SecondPresenter
import com.kite.demo.ui.rememberGreetingViewModel
import com.kite.di.compose.injected as composeInjected
import com.kite.di.runtime.android.injected

/**
 * The Compose surface of the demo: the same inferred graph, consumed from
 * composables — `injected<T>()` for graph values, and the generated
 * `rememberGreetingViewModel(name = …)` adapter for ViewModels (runtime argument
 * + SavedStateHandle, retained by this fragment's ViewModelStore).
 */
class SecondFragment : Fragment() {

    private val presenter: SecondPresenter by injected()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            MaterialTheme {
                SecondScreen(
                    headline = presenter.headline(),
                    onBack = { findNavController().navigate(R.id.action_SecondFragment_to_FirstFragment) },
                )
            }
        }
    }
}

@Composable
private fun SecondScreen(headline: String, onBack: () -> Unit) {
    val analytics = composeInjected<Analytics>()                // graph value, host Activity's scope
    val greeting = rememberGreetingViewModel(name = "Compose")  // generated adapter
    val taps by greeting.taps.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(greeting.greeting)
        Button(onClick = {
            greeting.onTap()
            analytics.track("compose_tap:${taps + 1}")
        }) {
            Text("Taps: $taps (rotate me!)")
        }
        Button(onClick = onBack) {
            Text(stringResource(R.string.previous))
        }
    }
}
