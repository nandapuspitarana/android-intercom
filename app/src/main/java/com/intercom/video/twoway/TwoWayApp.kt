package com.intercom.video.twoway

import android.app.Application

/** Application entry point. Shared objects live in [AppContainer] (no global mutable statics). */
class TwoWayApp : Application() {
    /** Replaceable before the first activity starts so functional tests can inject a test container. */
    @Volatile
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
