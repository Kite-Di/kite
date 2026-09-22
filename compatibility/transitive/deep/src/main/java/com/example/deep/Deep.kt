package com.example.deep

interface DeepApi { fun hello(): String }

class RealDeepApi : DeepApi {
    override fun hello() = "deep"
}
