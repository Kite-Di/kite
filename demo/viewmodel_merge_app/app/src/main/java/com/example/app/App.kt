package com.example.app

import android.app.Application
import com.kite.di.generated.Graph

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this)
    }
}
