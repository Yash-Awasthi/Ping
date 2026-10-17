package com.ping.app

import android.app.Application
import android.os.Process
import com.ping.app.auth.GestureCamera
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class PingApplication : Application() {

    @Inject lateinit var gestureCamera: GestureCamera

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Ping starting — v%s", BuildConfig.VERSION_NAME)
        thread(name = "hand-model") {
            // Background priority keeps the model load from competing with the first frame.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            gestureCamera.warmUp()
        }
    }
}
