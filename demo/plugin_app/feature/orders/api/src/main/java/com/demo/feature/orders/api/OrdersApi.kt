package com.demo.feature.orders.api

interface OrdersRepository {
    fun latest(): String
}
