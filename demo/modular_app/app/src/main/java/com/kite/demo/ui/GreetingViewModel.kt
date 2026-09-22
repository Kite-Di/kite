package com.kite.demo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.kite.demo.feature.profile.api.UserRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * A ViewModel showing all three parameter kinds at once (inference rule R2):
 *  - [repository] comes from the graph (interface → sole implementation),
 *  - [name] is a runtime argument — it becomes a parameter of the generated
 *    adapters (`rememberGreetingViewModel(name = …)`), no assisted injection,
 *  - [saved] is a SavedStateHandle, supplied from CreationExtras — the tap count
 *    survives rotation via the store and process death via saved state.
 */
class GreetingViewModel(
    repository: UserRepository,
    name: String,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val greeting: String = "Hello $name — ${repository.userName()}"

    val taps: StateFlow<Int> = saved.getStateFlow("taps", 0)

    fun onTap() {
        saved["taps"] = taps.value + 1
    }
}
