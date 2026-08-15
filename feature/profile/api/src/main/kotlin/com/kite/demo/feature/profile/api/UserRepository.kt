package com.kite.demo.feature.profile.api

/**
 * The profile feature's public contract. Downstream modules (:app's greeting
 * flow, other features) depend on this :api module only — the implementation
 * and its network/analytics dependencies stay behind :feature:profile:impl.
 */
interface UserRepository {
    fun userName(): String
}
