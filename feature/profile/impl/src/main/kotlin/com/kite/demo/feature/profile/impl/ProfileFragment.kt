package com.kite.demo.feature.profile.impl

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
import androidx.fragment.app.Fragment
import com.kite.demo.core.designsystem.AppCard
import com.kite.demo.core.designsystem.AppTheme
import com.kite.demo.core.designsystem.PrimaryButton
import com.kite.demo.core.designsystem.ScreenColumn
import com.kite.demo.core.designsystem.SectionHeader
import com.kite.demo.core.designsystem.StatusChip

/**
 * The profile screen: a Compose surface hosted in a fragment (so the app shell's
 * navigation graph can route to it), themed by :core:designsystem, fed by the
 * generated `rememberProfileViewModel()` adapter — retained by this fragment's
 * ViewModelStore across rotation.
 */
class ProfileFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AppTheme {
                ProfileScreen()
            }
        }
    }
}

@Composable
private fun ProfileScreen(viewModel: ProfileViewModel = rememberProfileViewModel()) {
    val state by viewModel.state.collectAsState()

    ScreenColumn {
        SectionHeader("Account")
        AppCard {
            Text(state.userName, style = MaterialTheme.typography.headlineSmall)
            StatusChip(if (state.syncCount == 0) "cached" else "synced")
            Text(
                "Fetched through ApiClient → HttpClient (:core:network); every sync " +
                    "is tracked by :core:analytics.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SectionHeader("Actions")
        PrimaryButton(
            text = if (state.syncCount == 0) "Sync profile" else "Synced ${state.syncCount}×",
            onClick = viewModel::onSync,
        )
    }
}
