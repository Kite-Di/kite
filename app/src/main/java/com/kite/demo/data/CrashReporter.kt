package com.kite.demo.data

import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Singleton
import com.kite.di.runtime.Provider

@Injectable
@Singleton
class CrashReporter @Inject constructor(
    private val analytics: Provider<Analytics>,
) {
    fun report(t: Throwable) {
        analytics.get().track("crash:${t.javaClass.simpleName}")
    }
}
