package com.netforce.ims

import android.app.Application

class Application : Application() {
    override fun onTerminate() {
        super.onTerminate()
        LogcatRepository.stopAndClear()
    }
}
