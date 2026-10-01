package com.intercom.video.twoway.service

import java.security.MessageDigest

/**
 * DEBUG BUILDS ONLY. Falls back to a fixed shared secret for any device that is not really paired, so
 * two debug phones can call each other before the pairing UI is used (US1 testing, tasks T068).
 * The release source set has its own factory that returns the real store unchanged; never copy this
 * class into src/main.
 */
object TrustStoreFactory {
    private val DEBUG_SECRET: ByteArray =
        MessageDigest.getInstance("SHA-256").digest("twoway-debug-pairing-secret".toByteArray())

    fun create(real: TrustStore): TrustStore = TrustStore { id -> real.pairingSecret(id) ?: DEBUG_SECRET.copyOf() }
}
