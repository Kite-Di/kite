package com.kite.demo.core.analytics

class CrashReporter(
    /** Plain function type — deferred lookup, no framework import (same as Provider<Analytics>). */
    private val analytics: () -> Analytics,
) {
    fun report(t: Throwable) {
        analytics().track("crash:${t.javaClass.simpleName}")
    }
}
