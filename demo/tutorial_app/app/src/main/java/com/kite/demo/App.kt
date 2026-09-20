package com.kite.demo

import android.app.Application
import com.kite.demo.di.AppInitializer
import com.kite.di.generated.Graph
import com.kite.di.runtime.Kite

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The generated startup façade. Its parameters are the graph arguments —
        // leaf constructor params (apiKey, timeoutMillis) nothing in the graph
        // provides, bubbled up by inference rule R5. The graph itself stays on the
        // dev machine — view it with `cd webboard && npm run board`.
        Graph.start(this, apiKey = "demo-key-123", timeoutMillis = 5_000)
        Kite.get<AppInitializer>().runAll()
    }
}
