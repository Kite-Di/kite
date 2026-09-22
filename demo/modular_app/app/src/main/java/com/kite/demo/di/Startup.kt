package com.kite.demo.di

import android.util.Log
import com.kite.demo.core.analytics.Analytics
import com.kite.demo.core.network.PayloadDecoder
import com.kite.demo.core.network.RequestCache

/**
 * The classic plugin pattern with zero framework concepts: implement the
 * interface, and `Set<StartupTask>` collects every implementation (inferred
 * multibinding — what used to need `@IntoSet`).
 */
interface StartupTask {
    val name: String
    fun run()
}

class WarmUpCacheTask(private val cache: RequestCache) : StartupTask {
    override val name = "warm-up-cache"
    override fun run() {
        Log.d("Startup", "cache ready: ${cache.describe()}")
    }
}

class TrackLaunchTask(private val analytics: Analytics) : StartupTask {
    override val name = "track-launch"
    override fun run() = analytics.track("app_launched")
}

class ProbeParsersTask(private val decoder: PayloadDecoder) : StartupTask {
    override val name = "probe-parsers"
    override fun run() {
        Log.d("Startup", "payload formats: ${decoder.formats()}")
    }
}

/** What `App.onCreate` asks for — resolved by interface, like every other consumer. */
interface AppInitializer {
    fun runAll()
}

/**
 * Receives every implementation — a new task class appears here (and on the
 * board) automatically. Resolved only at runtime (`Kite.get<AppInitializer>()`),
 * yet it needs no `@Root` decision: implementing [AppInitializer] already puts it
 * in the graph (R1).
 */
class SequentialAppInitializer(private val tasks: Set<StartupTask>) : AppInitializer {
    override fun runAll() {
        for (task in tasks) {
            Log.d("Startup", "running ${task.name}")
            task.run()
        }
    }
}
