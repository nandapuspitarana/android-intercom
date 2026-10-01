package com.intercom.video.twoway.service

/** Release builds use the real paired-devices store only: no fallback secrets. */
object TrustStoreFactory {
    fun create(real: TrustStore): TrustStore = real
}
