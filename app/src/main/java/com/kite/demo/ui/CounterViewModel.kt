package com.kite.demo.ui

import androidx.lifecycle.ViewModel
import com.kite.demo.data.Analytics
import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable

/**
 * An androidx ViewModel built by the injector: unscoped in the graph (the
 * ViewModelStore is the cache — a scope annotation here would be a build error),
 * retained across rotation, cleared when the Activity truly finishes.
 */
@Injectable
class CounterViewModel @Inject constructor(
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
