package vn.vietbot.client

import android.app.Application
import android.os.Looper
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VApplication : Application() {
    companion object {
        private const val TAG = "VApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VApplication onCreate")
        Log.d(TAG, "Main thread: ${Looper.getMainLooper().thread.name}")
        Log.d(TAG, "Main thread id: ${Looper.getMainLooper().thread.id}")

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            throwable.printStackTrace()
        }
    }
}