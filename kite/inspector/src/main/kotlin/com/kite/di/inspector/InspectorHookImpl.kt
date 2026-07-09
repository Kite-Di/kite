package com.kite.di.inspector

import android.content.Context
import com.kite.di.runtime.KiteConfig
import com.kite.di.runtime.InspectorHook
import com.kite.di.runtime.InspectorRuntimeAccess

/** Discovered by the runtime via ServiceLoader (debug builds only). */
class InspectorHookImpl : InspectorHook {
    override fun start(context: Context, config: KiteConfig, access: InspectorRuntimeAccess) {
        Inspector.start(context, config, access)
    }

    override fun stop() {
        Inspector.stop()
    }
}
