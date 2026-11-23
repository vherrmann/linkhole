package com.vherrmann.thenextthing

import android.app.Application
import com.vherrmann.thenextthing.data.Repository

class App : Application() {
    val repo by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        repo.scheduleSync()
    }
}
