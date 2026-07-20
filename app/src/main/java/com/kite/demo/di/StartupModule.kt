package com.kite.demo.di

import android.util.Log
import com.kite.demo.data.Analytics
import com.kite.demo.data.PayloadDecoder
import com.kite.demo.data.RequestCache
import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.IntoSet
import com.kite.di.annotations.Module
import com.kite.di.annotations.Provides
import com.kite.di.annotations.Singleton

/** The classic multibinding plugin pattern: contribute tasks, inject the whole set. */
interface StartupTask {
    val name: String
    fun run()
}

@Module
object StartupModule {

    @Provides
    @IntoSet
    fun warmUpCache(cache: RequestCache): StartupTask = object : StartupTask {
        override val name = "warm-up-cache"
        override fun run() {
            Log.d("Startup", "cache ready: ${cache.describe()}")
        }
    }

    @Provides
    @IntoSet
    fun trackLaunch(analytics: Analytics): StartupTask = object : StartupTask {
        override val name = "track-launch"
        override fun run() = analytics.track("app_launched")
    }

    @Provides
    @IntoSet
    fun probeParsers(decoder: PayloadDecoder): StartupTask = object : StartupTask {
        override val name = "probe-parsers"
        override fun run() {
            Log.d("Startup", "payload formats: ${decoder.formats()}")
        }
    }
}

/** Receives every @IntoSet contribution — a new task appears here (and on the board) automatically. */
@Injectable
@Singleton
class AppInitializer @Inject constructor(
    private val tasks: Set<StartupTask>,
) {
    fun runAll() {
        for (task in tasks) {
            Log.d("Startup", "running ${task.name}")
            task.run()
        }
    }
}
