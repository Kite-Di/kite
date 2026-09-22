package com.kite.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.kite.demo.core.analytics.Analytics
import com.kite.demo.core.designsystem.AppTheme
import com.kite.demo.core.designsystem.PrimaryButton
import com.kite.demo.core.designsystem.ScreenColumn
import com.kite.demo.core.designsystem.SecondaryButton
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
            AppTheme {
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

    ScreenColumn {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(greeting.greeting, style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(
            text = "Taps: $taps (rotate me!)",
            onClick = {
                greeting.onTap()
                analytics.track("compose_tap:${taps + 1}")
            },
        )
        SecondaryButton(text = stringResource(R.string.previous), onClick = onBack)
    }
}
