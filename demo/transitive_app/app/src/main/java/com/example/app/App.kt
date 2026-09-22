package com.example.app

import android.app.Application
import com.example.mid.MidApi
import com.kite.di.generated.Graph

interface Greeter { fun greet(): String }

class RealGreeter(private val mid: MidApi) : Greeter {
    override fun greet() = "app+" + mid.hello()
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
