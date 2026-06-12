package com.dhairya.newsmemory

import android.app.Application
import androidx.work.Configuration
import com.dhairya.newsmemory.capture.ListenerRebinderWorker

/** On-demand WorkManager init (Configuration.Provider) so scheduling in onCreate is safe. */
class App : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ListenerRebinderWorker.schedule(this)
    }
}
