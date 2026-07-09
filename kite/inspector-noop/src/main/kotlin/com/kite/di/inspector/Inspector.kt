package com.kite.di.inspector

/**
 * Release stand-in for the inspector: same public surface as
 * `:kite:inspector`'s [Inspector], empty bodies, zero dependencies. It also
 * registers no `InspectorHook` service, so `Kite.init()` finds no inspector to
 * start — no Ktor, no web assets, no server code in release APKs.
 */
object Inspector {
    /** Always null: no server runs in release builds. */
    val url: String? = null

    fun stop() = Unit
}
