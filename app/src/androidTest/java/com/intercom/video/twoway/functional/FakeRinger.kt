package com.intercom.video.twoway.functional

import com.intercom.video.twoway.service.Ringer
import java.util.concurrent.atomic.AtomicInteger

/** Records ringing instead of playing a sound, so tests can assert that a call rang and when it stopped. */
class FakeRinger : Ringer {
    @Volatile
    override var isRinging = false
        private set

    val startCount = AtomicInteger()

    @Volatile
    var lastRingtoneUri: String? = null
        private set

    override fun start(ringtoneUri: String?) {
        if (isRinging) return
        isRinging = true
        lastRingtoneUri = ringtoneUri
        startCount.incrementAndGet()
    }

    override fun stop() {
        isRinging = false
    }
}
