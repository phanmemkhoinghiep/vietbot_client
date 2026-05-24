package vn.vietbot.client

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}