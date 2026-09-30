package com.example.viewmodel

import androidx.lifecycle.ViewModel
import com.example.data.Repository

/**
 * The module's entire graph content. R2 makes it an entry point with generated
 * `featureViewModel()` adapters and *not* a binding, so this module's registry
 * exports no keys — while the adapters resolve [Repository], which lives in :data.
 */
class FeatureViewModel(private val repository: Repository) : ViewModel() {
    fun text(): String = repository.load()
}
