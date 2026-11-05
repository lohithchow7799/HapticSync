package com.hapticsync.app

import android.app.Application
import android.content.Context

/** Creates the process-wide [AppContainer]. */
class HapticSyncApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** The process-wide dependency container. */
val Context.appContainer: AppContainer
    get() = (applicationContext as HapticSyncApplication).container
