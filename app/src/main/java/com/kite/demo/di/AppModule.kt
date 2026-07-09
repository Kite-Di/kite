package com.kite.demo.di

import com.kite.demo.data.HttpClient
import com.kite.demo.data.RequestCache
import com.kite.di.annotations.Module
import com.kite.di.annotations.Named
import com.kite.di.annotations.Provides
import com.kite.di.annotations.Singleton

/**
 * Dagger-style module for values that need construction logic or qualifiers.
 * Auto-discovered — nothing to register.
 */
@Module
object AppModule {

    @Provides
    @Singleton
    @Named("apiKey")
    fun apiKey(): String = "demo-key-123"

    @Provides
    @Singleton
    fun httpClient(cache: RequestCache): HttpClient = HttpClient(cache, timeoutMillis = 5_000)
}
