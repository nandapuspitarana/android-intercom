package com.intercom.video.twoway.functional

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.core.pairing.PairingLink
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PairingUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * US4 through the real UI: an unpaired phone can never make this one ring; pairing with a 6-digit code (typed, or from a
 * scanned QR payload) marks the peer trusted; mismatches, an unknown identity and removal are all refused.
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class PairingFunctionalTest {
    /** Starts UNPAIRED: each test sets up the trust it needs. */
    private val setup = FunctionalTestRule(pairPeer = false)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private fun trusted(id: String) = runBlocking { setup.container.pairedDevices.isTrusted(id) }

    private fun codeShownByTheApp(): String {
        val node = compose.onNodeWithTag("pairing_code").fetchSemanticsNode()
        val text = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        return text.filter { it.isDigit() }
    }

    private fun peerCode() = (setup.peer.device.pairing.state.value as PairingUiState.Verify).code

    private fun peerLink(fingerprint: ByteArray? = null) = PairingLink(
        setup.peer.deviceId,
        "127.0.0.1",
        setup.peer.address.port,
        fingerprint ?: MessageDigest.getInstance("SHA-256").digest(setup.peer.device.identity.publicKey).copyOf(16),
        setup.peer.name,
    ).encode()

    private fun openPairedScreenAndPaste(text: String) {
        compose.waitForTag("open_paired")
        compose.onNodeWithTag("open_paired").performClick()
        compose.waitForTag("pairing_link_input")
        compose.onNodeWithTag("pairing_link_input").performTextInput(text)
        compose.onNodeWithTag("pairing_link_go").performClick()
    }

    @Test
    fun anUnpairedPeerCanNeverMakeThePhoneRing() {
        // The peer believes it is paired (it has a secret) but the app never agreed to that secret.
        setup.peer.device.trust.trust(setup.container.self.deviceId, setup.secret)
        setup.launch()
        compose.waitForTag("devices_empty")

        setup.peer.callApp(setup.appAddress())

        assertTrue("the peer is told pairing is required", await { setup.peer.device.records.isNotEmpty() })
        assertEquals(EndReason.NOT_PAIRED, setup.peer.device.records.last().reason)
        Thread.sleep(500)
        assertFalse("no ringing", setup.container.ringer.isRinging)
        assertFalse("no incoming-call screen", compose.hasTag("incoming_title"))
        assertTrue(setup.container.runtimeOrNull!!.engine.state.value is CallUiState.Idle)
    }

    @Test
    fun pairingWithMatchingCodesMakesThePeerTrustedAndTheNextCallRings() {
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("pair_${setup.peer.deviceId}") // unpaired: offers Pair, not Call
        assertFalse(compose.hasTag("call_${setup.peer.deviceId}"))

        compose.onNodeWithTag("pair_${setup.peer.deviceId}").performClick()
        compose.waitForTag("pairing_code")

        // Both screens show the same 6 digits.
        assertTrue(await { setup.peer.device.pairing.state.value is PairingUiState.Verify })
        assertEquals(peerCode(), codeShownByTheApp())
        assertEquals(6, codeShownByTheApp().length)

        compose.onNodeWithTag("pairing_matches").performClick()
        compose.waitForTag("pairing_done")
        compose.onNodeWithTag("pairing_done").assertTextContains("Paired with Peer Phone")
        assertTrue("stored as trusted", await { trusted(setup.peer.deviceId) })
        compose.onNodeWithTag("pairing_ok").performClick()

        // The list now offers Call and shows the Paired badge.
        compose.waitForTag("call_${setup.peer.deviceId}")
        compose.onNodeWithTag("paired_badge_${setup.peer.deviceId}").assertIsDisplayed()

        // The freshly paired peer can ring the phone, using only the secret just agreed.
        setup.peer.callApp(setup.appAddress())
        compose.waitForTag("incoming_title")
        compose.onNodeWithTag("accept_call").performClick()
        compose.waitForTag("end_call")
        assertTrue(await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 })
        compose.onNodeWithTag("end_call").performClick()
        compose.waitForTag("ended_message")
    }

    @Test
    fun doesNotMatchAbortsAndStoresNothing() {
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("pair_${setup.peer.deviceId}")
        compose.onNodeWithTag("pair_${setup.peer.deviceId}").performClick()
        compose.waitForTag("pairing_code")

        // The user sees a different code on the other phone and presses "Does not match".
        compose.onNodeWithTag("pairing_not_matches").performClick()
        compose.waitForTag("pairing_failed")
        compose.onNodeWithTag("pairing_failed").assertTextContains("The codes did not match. Nothing was saved.")
        assertFalse(trusted(setup.peer.deviceId))
        assertTrue(setup.peer.device.pairingStore.saved.isEmpty())
        compose.onNodeWithTag("pairing_ok").performClick()
        compose.waitForTag("pair_${setup.peer.deviceId}")
    }

    @Test
    fun whenThePeerSaysTheCodesDoNotMatchTheAppDoesNotPairEither() {
        setup.peer.pairingBehavior = FakePeerDevice.PairingBehavior.CONFIRM_MISMATCH
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("pair_${setup.peer.deviceId}")
        compose.onNodeWithTag("pair_${setup.peer.deviceId}").performClick()

        // The peer rejects the code straight away, so the app may or may not show the code screen first; it must end failed.
        compose.waitForTag("pairing_failed")
        compose.onNodeWithTag("pairing_failed").assertTextContains("The codes did not match. Nothing was saved.")
        assertFalse(trusted(setup.peer.deviceId))
        assertTrue(setup.peer.device.pairingStore.saved.isEmpty())
    }

    @Test
    fun aScannedQrPayloadStartsTheSameFlow() {
        setup.launch()
        compose.waitForTag("devices_empty")
        openPairedScreenAndPaste(peerLink()) // exactly what the camera scanner reports
        compose.waitForTag("pairing_code")
        assertEquals(peerCode(), codeShownByTheApp())
        compose.onNodeWithTag("pairing_matches").performClick()
        compose.waitForTag("pairing_done")
        assertTrue(await { trusted(setup.peer.deviceId) })
    }

    @Test
    fun aQrCodeForADifferentPhoneIsRefused() {
        setup.launch()
        compose.waitForTag("devices_empty")
        // The QR claims a fingerprint that is not the peer's real identity.
        openPairedScreenAndPaste(peerLink(fingerprint = ByteArray(16) { 7 }))
        compose.waitForTag("pairing_code")
        compose.onNodeWithTag("pairing_matches").performClick()
        compose.waitForTag("pairing_failed")
        compose.onNodeWithTag("pairing_failed").assertTextContains("This is not the phone in the QR code. Nothing was saved.")
        assertFalse(trusted(setup.peer.deviceId))
    }

    @Test
    fun textThatIsNotAPairingCodeIsRejectedWithAMessage() {
        setup.launch()
        compose.waitForTag("devices_empty")
        openPairedScreenAndPaste("https://example.com/not-a-code")
        compose.waitForText("That is not a Two Way pairing code.")
        assertEquals(PairingUiState.Idle, setup.container.runtimeOrNull!!.pairing.state.value)
    }

    @Test
    fun aKnownDeviceThatNowPresentsADifferentIdentityIsRefusedWithAWarning() {
        // The app already trusts this device id, pinned to ANOTHER identity key (someone is reusing the id).
        val otherIdentity = com.intercom.video.twoway.testing.TestIdentity(setup.peer.deviceId, "Impostor")
        setup.container.pairedDevices.savePaired(setup.peer.deviceId, setup.peer.name, otherIdentity.publicKey, ByteArray(32) { 5 })
        setup.launch()
        compose.waitForTag("devices_empty")

        openPairedScreenAndPaste(peerLink())
        compose.waitForTag("pairing_code")
        compose.onNodeWithTag("pairing_matches").performClick()
        compose.waitForTag("pairing_failed")
        compose.onNodeWithTag("pairing_failed").assertTextContains("different identity", substring = true)
        // the old, pinned entry is untouched
        assertTrue(trusted(setup.peer.deviceId))
    }

    @Test
    fun removingAPairedDeviceMakesItsCallsRefusedAgain() {
        setup.pairWithPeer()
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("call_${setup.peer.deviceId}") // paired

        compose.onNodeWithTag("open_paired").performClick()
        compose.waitForTag("paired_${setup.peer.deviceId}")
        compose.onNodeWithTag("remove_${setup.peer.deviceId}").performClick()
        compose.waitForTag("remove_confirm")
        compose.onNodeWithTag("remove_confirm").performClick()
        assertTrue("no longer trusted", await { !trusted(setup.peer.deviceId) })

        // The peer still has its secret, but the phone no longer accepts it.
        setup.peer.callApp(setup.appAddress())
        assertTrue(await { setup.peer.device.records.isNotEmpty() })
        assertEquals(EndReason.NOT_PAIRED, setup.peer.device.records.last().reason)
        assertFalse(setup.container.ringer.isRinging)

        // Back on the list the device offers Pair again.
        compose.onNodeWithTag("paired_back").performClick()
        compose.waitForTag("pair_${setup.peer.deviceId}")
    }

    @Test
    fun aPairedDeviceCanBeRenamedLocally() {
        setup.pairWithPeer()
        setup.launch()
        compose.waitForTag("devices_empty")
        compose.onNodeWithTag("open_paired").performClick()
        compose.waitForTag("rename_${setup.peer.deviceId}")
        compose.onNodeWithTag("rename_${setup.peer.deviceId}").performClick()
        compose.waitForTag("rename_input")
        compose.onNodeWithTag("rename_input").performTextClearance()
        compose.onNodeWithTag("rename_input").performTextInput("Dapur Ibu")
        compose.onNodeWithTag("rename_save").performClick()
        compose.waitForText("Dapur Ibu")
    }

    @Test
    fun withIgnoreUnpairedOnTheCallerGetsNoHintThatPairingIsNeeded() {
        runBlocking { setup.container.settings.setAutoRejectUnknown(true) }
        setup.peer.device.trust.trust(setup.container.self.deviceId, setup.secret)
        setup.launch()
        compose.waitForTag("devices_empty")
        // the service applies the setting to the engine
        assertTrue(await { setup.container.runtimeOrNull!!.engine.autoRejectUnknown })

        setup.peer.callApp(setup.appAddress())
        assertTrue(await { setup.peer.device.records.isNotEmpty() })
        assertEquals("silent refusal reads as unavailable, not NOT_PAIRED", EndReason.UNAVAILABLE, setup.peer.device.records.last().reason)
        assertFalse(setup.container.ringer.isRinging)
    }
}
