package com.kite.demo.data

import com.kite.di.annotations.Inject
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Singleton

@Injectable
@Singleton
class CrashReporter @Inject constructor(
    /** Plain function type — deferred lookup, no framework import (same as Provider<Analytics>). */
    private val analytics: () -> Analytics,
) {
    fun report(t: Throwable) {
        analytics().track("crash:${t.javaClass.simpleName}")
    }
}
