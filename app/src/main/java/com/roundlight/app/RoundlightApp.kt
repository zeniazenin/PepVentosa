package com.roundlight.app

import android.app.Application
import com.roundlight.app.data.Diagnostics

class RoundlightApp: Application() {
    lateinit var log: Diagnostics
    override fun onCreate() { super.onCreate();log=Diagnostics(this);log.event("app.start")
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error -> runCatching { log.failure("app.crash",error) }; previous?.uncaughtException(thread,error) }
    }
}
