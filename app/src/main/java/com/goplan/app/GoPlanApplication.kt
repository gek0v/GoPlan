package com.goplan.app

import android.app.Application

class GoPlanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GoPlanApplication
            private set
    }
}
