package com.ping.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class PingApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Ping starting — v%s", BuildConfig.VERSION_NAME)
    }
}
