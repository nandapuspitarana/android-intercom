package com.intercom.video.twoway.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class Trust { TRUSTED, REVOKED }

/** A device the user confirmed through pairing (spec: Paired Device). */
@Entity(tableName = "paired_device")
class PairedDevice(
    /** Peer's 16-byte id as hex. */
    @PrimaryKey val deviceId: String,
    /** 1-32 chars, sanitized; the user may rename it locally. */
    val displayName: String,
    /** SHA-256 of the peer's long-term public key (32 bytes); pinned on every handshake. */
    val publicKeyFingerprint: ByteArray,
    /** The 32-byte pairing secret PS, encrypted with a keystore key. Never logged. */
    val pairingSecret: ByteArray,
    val trust: Trust,
    val pairedAt: Long,
    /** Hint only; never trusted for identity. */
    val lastSeenAddress: String?,
)
