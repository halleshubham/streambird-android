package app.streambird.android

import android.app.Application
import app.streambird.android.diag.CrashReporter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StreamBirdApp : Application() {
    /** Injected here so the settings load starts on a background thread at app start, before any screen needs it. */
    @javax.inject.Inject lateinit var settings: app.streambird.android.data.Settings

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)          // Java exception handler + event ring log
        CrashReporter.collectPrevious(this)  // exit reason / tombstone of the previous run -> pending report for the Setup screen
    }
}
