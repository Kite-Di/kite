package com.demo.core.plugin

/**
 * The aggregators live beside the contracts, not in :app — the application shell
 * only asks for them, and never learns which features answered.
 */

interface Startup {
    fun runAll(): List<String>
}

class OrderedStartup(private val tasks: Set<StartupTask>) : Startup {
    override fun runAll(): List<String> =
        tasks.sortedBy { it.priority }.map { task -> task.run(); task.name }
}

interface Router {
    /** Null when no feature claims the host — the caller decides what that means. */
    fun open(uri: String): String?
}

class HostRouter(private val handlers: Set<DeepLinkHandler>) : Router {
    override fun open(uri: String): String? {
        val withoutScheme = uri.substringAfter("://", uri)
        val host = withoutScheme.substringBefore('/')
        val path = withoutScheme.substringAfter('/', "")
        return handlers.firstOrNull { it.host == host }?.open(path)
    }
}

interface SettingsCatalog {
    fun titles(): List<String>
}

class SortedSettingsCatalog(private val entries: Set<SettingsEntry>) : SettingsCatalog {
    override fun titles(): List<String> = entries.sortedBy { it.order }.map { it.title }
}
