package com.demo.pluginapp

import android.app.Application
import com.demo.core.plugin.Router
import com.demo.core.plugin.SettingsCatalog
import com.demo.core.plugin.Startup
import com.kite.di.generated.Graph
import com.kite.di.runtime.Kite

/**
 * The whole shell. It names no feature and imports from no feature module — it
 * asks :core:plugin for the aggregators, and whichever features are on the build
 * path answered. Deleting a feature from the dependency list is the whole removal.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, baseUrl = "https://api.example.com")

        Kite.get<Startup>().runAll()
    }
}

class Shell(
    private val router: Router,
    private val settings: SettingsCatalog,
) {
    fun open(uri: String): String = router.open(uri) ?: "unhandled: $uri"
    fun settingsTitles(): List<String> = settings.titles()
}
