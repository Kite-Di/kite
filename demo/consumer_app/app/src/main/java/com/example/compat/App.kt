package com.example.compat

import android.app.Application
import com.kite.di.generated.Graph

// Enough graph to exercise inference, a graph argument and generated code.
interface UserRepo { fun name(): String }

class Api(private val apiKey: String)

class RealUserRepo(private val api: Api) : UserRepo {
    override fun name() = "ada"
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.start(this, apiKey = "compat")
        check(userRepo().value.name() == "ada")
    }
}
