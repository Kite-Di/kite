package com.kite.demo.ui

import androidx.lifecycle.ViewModel
import com.kite.demo.core.analytics.Analytics

/**
 * An androidx ViewModel — an entry point (inference rule R2). The processor
 * generates a `counterViewModel()` adapter for Activities and Fragments; the
 * ViewModelStore retains the instance across rotation and clears it when the
 * Activity truly finishes. Graph scopes never apply to ViewModels — the store is
 * the cache.
 */
class CounterViewModel(
    private val analytics: Analytics,
) : ViewModel() {

    var clicks: Int = 0
        private set

    fun onFabClick(): Int {
        clicks++
        analytics.track("fab_clicks:$clicks")
        return clicks
    }
}
