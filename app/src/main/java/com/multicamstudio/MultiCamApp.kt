package com.multicamstudio

import android.app.Application
import com.multicamstudio.services.Notifications

class MultiCamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
        container.onCreate()
    }
}
