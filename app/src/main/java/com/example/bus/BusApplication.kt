package com.example.bus

import android.app.Application
import com.example.bus.tdx.TdxClient
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TdxClient.initialize(this)
    }
}
