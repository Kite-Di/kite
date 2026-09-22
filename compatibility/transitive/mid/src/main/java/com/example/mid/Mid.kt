package com.example.mid

import com.example.deep.DeepApi

interface MidApi { fun hello(): String }

/** Its dependency is what the app cannot reach when :deep is implementation-only. */
class RealMidApi(private val deep: DeepApi) : MidApi {
    override fun hello() = "mid+" + deep.hello()
}
