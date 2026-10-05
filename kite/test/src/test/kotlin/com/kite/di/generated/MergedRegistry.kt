package com.kite.di.generated

import com.kite.di.runtime.BindingRecord
import com.kite.di.runtime.BindingRegistry
import com.kite.di.runtime.Factory
import com.kite.di.runtime.Key
import com.kite.di.runtime.Resolver
import com.kite.di.runtime.ScopeNode
import com.kite.di.test.Counter
import com.kite.di.test.RealCounter
import com.kite.di.test.Screen
import com.kite.di.test.Special
import com.kite.di.test.SpecialCounter
import com.kite.di.test.SpecialScreen

// Hand-written stand-in for the registry aggregate the processor generates, found by
// Kite.init under this exact name.
object MergedRegistry {
    fun load(): List<BindingRegistry> = listOf(object : BindingRegistry {
        override fun bindings() = listOf(
            BindingRecord(
                Key(RealCounter::class.java),
                object : Factory<RealCounter> {
                    override fun create(resolver: Resolver, scope: ScopeNode) = RealCounter()
                },
                extraKeys = listOf(Key(Counter::class.java)),
                scopeLevel = 0,
                scopeName = "Singleton",
                declaration = "RealCounter",
            ),
            // A marked implementation: the interface under the mark, its class as itself.
            BindingRecord(
                Key(SpecialCounter::class.java),
                object : Factory<SpecialCounter> {
                    override fun create(resolver: Resolver, scope: ScopeNode) = SpecialCounter()
                },
                extraKeys = listOf(Key(Counter::class.java, mark = Special::class.java)),
                scopeLevel = 0,
                scopeName = "Singleton",
                declaration = "SpecialCounter",
            ),
            BindingRecord(
                Key(SpecialScreen::class.java),
                object : Factory<SpecialScreen> {
                    override fun create(resolver: Resolver, scope: ScopeNode) =
                        SpecialScreen(resolver.resolve(Key(Counter::class.java, mark = Special::class.java), scope))
                },
                declaration = "SpecialScreen",
            ),
            BindingRecord(
                Key(Screen::class.java),
                object : Factory<Screen> {
                    override fun create(resolver: Resolver, scope: ScopeNode) =
                        Screen(resolver.resolve(Key(Counter::class.java), scope))
                },
                declaration = "Screen",
            ),
        )
    })
}
