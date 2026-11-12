package com.workbuddy.localmodel

import android.app.Application
import com.workbuddy.localmodel.model.ModelRepository
import com.workbuddy.localmodel.model.SettingsStore

class LocalModelApp : Application() {

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(this)
        settings = SettingsStore(this)
    }
}
