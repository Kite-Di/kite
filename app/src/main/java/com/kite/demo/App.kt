package com.kite.demo

import android.app.Application
import com.kite.demo.di.AppInitializer
import com.kite.di.runtime.Kite

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads the KSP-generated MergedRegistry. The graph itself stays on the dev
        // machine — view it with `cd webboard && npm run board`.
        Kite.init(this)
        Kite.get<AppInitializer>().runAll()
    }
}
