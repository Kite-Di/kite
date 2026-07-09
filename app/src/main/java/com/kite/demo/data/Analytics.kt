package com.kite.demo.data

import android.util.Log
import com.kite.di.annotations.Injectable
import com.kite.di.annotations.Singleton

@Injectable
@Singleton
class Analytics {
    fun track(event: String) {
        Log.d("Analytics", "event: $event")
    }
}
