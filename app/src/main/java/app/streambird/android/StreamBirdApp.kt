package app.streambird.android

import android.app.Application
import app.streambird.android.diag.CrashReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StreamBirdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)          // Java exception handler + event ring log
        CrashReporter.collectPrevious(this)  // exit reason / tombstone of the previous run -> pending report for the Setup screen
    }
}
