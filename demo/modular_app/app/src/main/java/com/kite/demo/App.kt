package com.kite.demo

import android.app.Application
import com.kite.demo.di.appInitializer
import com.kite.di.generated.Graph

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The generated startup façade. Its parameters are the graph arguments —
        // leaf constructor params (apiKey, timeoutMillis) nothing in the graph
        // provides, bubbled up by inference rule R5. The graph itself stays on the
        // dev machine — view it with `cd webboard && npm run board`.
        Graph.start(this, apiKey = "demo-key-123", timeoutMillis = 5_000)
        // A generated accessor: it exists because AppInitializer has a binding, and on
        // an Application because that binding needs no Activity.
        appInitializer().value.runAll()
    }
}
