package com.example.dynamic

import android.app.Application
import com.kite.di.generated.Graph
import com.kite.di.runtime.Kite

interface Greeter {
    fun greet(): String
}

class RealGreeter(private val name: String) : Greeter {
    override fun greet(): String = "hello, $name"
}

/** Resolved only at runtime, so it takes a decision — which is what needs the vocabulary. */
class LegacyTracker {
    fun track(): String = "tracked"
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, name = "ada")
        check(Kite.get<Greeter>().greet() == "hello, ada")
        check(Kite.get<LegacyTracker>().track() == "tracked")
    }
}
