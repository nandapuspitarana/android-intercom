package com.intercom.video.twoway.service

import android.util.Log
import com.intercom.video.twoway.BuildConfig
import com.intercom.video.twoway.core.Logger

/** Logger backed by logcat; debug and warning output is only emitted in debug builds. */
object AndroidLogger : Logger {
    private const val PREFIX = "TwoWay/"

    override fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(PREFIX + tag, msg)
    }

    override fun w(tag: String, msg: String, t: Throwable?) {
        if (BuildConfig.DEBUG) Log.w(PREFIX + tag, msg, t)
    }

    override fun e(tag: String, msg: String, t: Throwable?) {
        Log.e(PREFIX + tag, msg, t)
    }
}
