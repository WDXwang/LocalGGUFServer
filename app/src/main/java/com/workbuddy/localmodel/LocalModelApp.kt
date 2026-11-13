package com.workbuddy.localmodel

import android.app.Application
import com.workbuddy.localmodel.llm.LlamaEngine
import com.workbuddy.localmodel.model.ModelRepository
import com.workbuddy.localmodel.model.SettingsStore
import com.workbuddy.localmodel.util.ThermalMonitor

class LocalModelApp : Application() {

    lateinit var modelRepository: ModelRepository
        private set

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        modelRepository = ModelRepository(this)
        settings = SettingsStore(this)
        LlamaEngine.attach(this)
        // 温控监听挂在 Application 上：进程活多久就监听多久，
        // 不依赖某个页面是否在前台
        ThermalMonitor.attach(this)
    }
}
