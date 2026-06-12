package com.dhairya.newsmemory

import android.app.Application
import androidx.work.Configuration
import com.dhairya.newsmemory.capture.ListenerRebinderWorker
import com.dhairya.newsmemory.pipeline.CatchupScheduler

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
        // Hourly catch-up + immediate pass: re-arms the alarm chain and closes any
        // window missed while the app was dead. Idempotent by window_id.
        CatchupScheduler.schedulePeriodic(this)
        CatchupScheduler.scheduleNow(this)
    }
}
