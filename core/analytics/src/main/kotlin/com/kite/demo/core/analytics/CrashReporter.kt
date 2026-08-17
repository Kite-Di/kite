package com.kite.demo.core.analytics

interface CrashReporter {
    fun report(t: Throwable)
}

class LoggingCrashReporter(
    /** Plain function type — deferred lookup, no framework import (same as Provider<Analytics>). */
    private val analytics: () -> Analytics,
) : CrashReporter {
    override fun report(t: Throwable) {
        analytics().track("crash:${t.javaClass.simpleName}")
    }
}
