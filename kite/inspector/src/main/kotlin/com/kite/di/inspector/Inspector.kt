package com.kite.di.inspector

import android.content.Context
import com.kite.di.runtime.KiteConfig
import com.kite.di.runtime.InspectorRuntimeAccess

/**
 * Public facade of the debug inspector. `Graph.start` starts it through the
 * ServiceLoader hook — there is nothing to call. The release artifact
 * (`:kite:inspector-noop`) has this same public surface with empty bodies, so code
 * that reads [url] compiles in both.
 */
object Inspector {
    /** `"http://127.0.0.1:<port>"` while running, null otherwise. */
    val url: String? get() = InspectorServer.url

    /** Returns whether the server actually started (it declines a non-debuggable app). */
    internal fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess): Boolean =
        InspectorServer.start(context, config, access)

    fun stop() = InspectorServer.stop()
}
