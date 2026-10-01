package com.intercom.video.twoway.core

/** Toggleable logger; core code never prints to stdout or calls printStackTrace. */
interface Logger {
    fun d(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}

object NoopLogger : Logger {
    override fun d(tag: String, msg: String) = Unit
    override fun w(tag: String, msg: String, t: Throwable?) = Unit
    override fun e(tag: String, msg: String, t: Throwable?) = Unit
}
