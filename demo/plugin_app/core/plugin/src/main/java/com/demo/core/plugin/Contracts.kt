package com.demo.core.plugin

/**
 * The extension points a feature can plug into. Nothing here knows a feature
 * exists; a feature module implements one of these and is thereby registered.
 */

/** Work that runs once at startup. Order is by [priority], lowest first. */
interface StartupTask {
    val name: String
    val priority: Int get() = 100
    fun run()
}

/** A screen reachable by URI. The key lives on the contract — no map, no @MapKey. */
interface DeepLinkHandler {
    val host: String
    fun open(path: String): String
}

/** Anything a feature wants to expose on the settings screen. */
interface SettingsEntry {
    val title: String
    val order: Int
}
