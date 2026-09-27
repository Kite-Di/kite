package com.example.data

interface Repository { fun load(): String }

class RealRepository : Repository {
    override fun load() = "data"
}
