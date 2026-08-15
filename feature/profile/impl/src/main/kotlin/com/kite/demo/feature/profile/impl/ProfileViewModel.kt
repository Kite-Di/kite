package com.kite.demo.feature.profile.impl

import androidx.lifecycle.ViewModel
import com.kite.demo.core.analytics.Analytics
import com.kite.demo.feature.profile.api.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProfileUiState(
    val userName: String,
    val syncCount: Int,
)

/**
 * An androidx ViewModel — an entry point (inference rule R2). The processor
 * generates `profileViewModel()` (Activities/Fragments) and, since this module
 * applies the Compose plugin, `rememberProfileViewModel()`. Both dependencies
 * resolve across module boundaries: [UserRepository] against this module's own
 * R1 binding, [Analytics] against :core:analytics's exported fragment.
 */
class ProfileViewModel(
    private val repository: UserRepository,
    private val analytics: Analytics,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState(repository.userName(), syncCount = 0))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    fun onSync() {
        analytics.track("profile_sync")
        _state.update { ProfileUiState(repository.userName(), it.syncCount + 1) }
    }
}
