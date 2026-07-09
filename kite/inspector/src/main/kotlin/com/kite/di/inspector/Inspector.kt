package com.kite.di.inspector

import android.content.Context
import com.kite.di.runtime.KiteConfig
import com.kite.di.runtime.InspectorRuntimeAccess

/**
 * Public facade of the debug inspector. Normally started automatically by
 * `Kite.init()` through the ServiceLoader hook; direct calls are for tools.
 * The release artifact (`:kite:inspector-noop`) has this same surface with
 * empty bodies.
 */
object Inspector {
    /** `"http://127.0.0.1:<port>"` while running, null otherwise. */
    val url: String? get() = InspectorServer.url

    fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess) =
        InspectorServer.start(context, config, access)

    fun stop() = InspectorServer.stop()
}
