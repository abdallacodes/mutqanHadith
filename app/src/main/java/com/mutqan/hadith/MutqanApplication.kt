package com.mutqan.hadith

import android.app.Application
import com.mutqan.hadith.di.AppContainer

class MutqanApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
