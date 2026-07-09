package com.kite.demo

import android.app.Application
import com.kite.di.runtime.Kite

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Loads the KSP-generated MergedRegistry and (in debug builds) starts the
        // inspector server — open http://localhost:8394 after `adb forward tcp:8394 tcp:8394`.
        Kite.init(this)
    }
}
