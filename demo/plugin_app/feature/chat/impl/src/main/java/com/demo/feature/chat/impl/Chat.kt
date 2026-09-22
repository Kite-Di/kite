package com.demo.feature.chat.impl

import com.demo.core.analytics.Analytics
import com.demo.core.plugin.DeepLinkHandler
import com.demo.core.plugin.StartupTask
import com.demo.feature.chat.api.ChatSession

class SocketChatSession(private val analytics: Analytics) : ChatSession {
    override fun connect(): String {
        analytics.track("chat_connected")
        return "socket"
    }
}

class ConnectSocketTask(private val session: ChatSession) : StartupTask {
    override val name = "connect-socket"
    override val priority = 90
    override fun run() { session.connect() }
}

class ChatDeepLink(private val session: ChatSession) : DeepLinkHandler {
    override val host = "chat"
    override fun open(path: String) = "chat:" + session.connect() + "/" + path
}
