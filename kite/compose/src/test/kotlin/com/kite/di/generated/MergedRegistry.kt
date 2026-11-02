package com.kite.di.generated

import com.kite.di.compose.ScreenState
import com.kite.di.compose.Tracker
import com.kite.di.runtime.BindingRecord
import com.kite.di.runtime.BindingRegistry
import com.kite.di.runtime.Factory
import com.kite.di.runtime.Key
import com.kite.di.runtime.Resolver
import com.kite.di.runtime.ScopeNode

// Hand-written stand-in for the registry aggregate the processor generates, found by
// Kite.init under this exact name.
object MergedRegistry {
    fun load(): List<BindingRegistry> = listOf(object : BindingRegistry {
        override fun bindings() = listOf(
            BindingRecord(
                Key(Tracker::class.java),
                object : Factory<Tracker> {
                    override fun create(resolver: Resolver, scope: ScopeNode) = Tracker()
                },
                scopeLevel = 0,
                scopeName = "Singleton",
                declaration = "Tracker",
            ),
            BindingRecord(
                Key(ScreenState::class.java),
                object : Factory<ScreenState> {
                    override fun create(resolver: Resolver, scope: ScopeNode) = ScreenState()
                },
                scopeLevel = 1,
                scopeName = "Activity",
                declaration = "ScreenState",
            ),
        )
    })
}
