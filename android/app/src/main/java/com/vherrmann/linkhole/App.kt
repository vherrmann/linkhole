package com.vherrmann.linkhole

import android.app.Application
import com.vherrmann.linkhole.data.Repository

class App : Application() {
    val repo by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        repo.scheduleSync()
    }
}
