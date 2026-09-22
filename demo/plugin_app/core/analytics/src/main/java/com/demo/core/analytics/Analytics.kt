package com.demo.core.analytics

import com.demo.core.plugin.StartupTask

interface Analytics {
    fun track(event: String)
}

class LogcatAnalytics : Analytics {
    private val events = mutableListOf<String>()
    override fun track(event: String) { events += event }
}

class TrackLaunchTask(private val analytics: Analytics) : StartupTask {
    override val name = "track-launch"
    override val priority = 20
    override fun run() = analytics.track("app_launched")
}
