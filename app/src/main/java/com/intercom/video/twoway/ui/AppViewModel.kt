package com.intercom.video.twoway.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.core.pairing.PairingLink
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.Device
import com.intercom.video.twoway.service.CallRuntime
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PairingUiState
import com.intercom.video.twoway.service.PeerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Bridges the service-hosted call runtime to Compose. Holds no call or pairing state of its own. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val container: AppContainer) : ViewModel() {
    private val runtime: StateFlow<CallRuntime?> = container.runtime

    val devices: StateFlow<List<Device>> = runtime
        .flatMapLatest { it?.registry?.devices ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val callState: StateFlow<CallUiState> = runtime
        .flatMapLatest { it?.engine?.state ?: flowOf(CallUiState.Idle) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CallUiState.Idle)

    val pairingState: StateFlow<PairingUiState> = runtime
        .flatMapLatest { it?.pairing?.state ?: flowOf(PairingUiState.Idle) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState.Idle)

    val pairedIds: StateFlow<Set<String>> = runtime
        .flatMapLatest { it?.pairedIds ?: flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val networkMode: StateFlow<NetworkMode> = runtime
        .flatMapLatest { it?.networkMode ?: flowOf(NetworkMode.NONE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkMode.NONE)

    val selfName: StateFlow<String> = runtime
        .map { it?.self?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Tapping a device: call it if it is paired, otherwise start pairing (calls to unpaired phones are refused). */
    fun call(device: Device) {
        if (device.deviceId in pairedIds.value) {
            runtime.value?.engine?.call(peerOf(device))
        } else {
            pair(device)
        }
    }

    fun pair(device: Device) {
        runtime.value?.pairing?.start(peerOf(device))
    }

    /** Pairs with the phone described by a scanned or pasted code. Returns false if the text is not a valid code. */
    fun pairFromCode(text: String): Boolean {
        val link = PairingLink.parse(text.trim()) ?: return false
        runtime.value?.pairing?.start(PeerAddress(link.deviceId, link.name, link.host, link.port), link.fingerprint)
        return true
    }

    /** Adds a phone by a typed address (HELLO probe on a background thread). */
    suspend fun addByAddress(text: String): CallRuntime.AddResult {
        val rt = runtime.value ?: return CallRuntime.AddResult.NO_ANSWER
        return withContext(Dispatchers.IO) { rt.addByAddress(text) }
    }

    fun setMuted(muted: Boolean) = runtime.value?.engine?.setMuted(muted)
    fun setRoute(route: com.intercom.video.twoway.core.media.AudioRoute) = runtime.value?.engine?.setRoute(route)

    fun accept() = runtime.value?.engine?.accept()
    fun reject() = runtime.value?.engine?.reject()
    fun hangup() = runtime.value?.engine?.hangup()
    fun dismissEnded() = runtime.value?.engine?.dismissEnded()

    fun acceptPairing() = runtime.value?.pairing?.acceptIncoming()
    fun declinePairing() = runtime.value?.pairing?.declineIncoming()
    fun confirmCode(matches: Boolean) = runtime.value?.pairing?.confirmMatches(matches)
    fun cancelPairing() = runtime.value?.pairing?.cancel()
    fun dismissPairing() = runtime.value?.pairing?.dismiss()

    private fun peerOf(d: Device) = PeerAddress(d.deviceId, d.displayName, d.host, d.port)

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}
