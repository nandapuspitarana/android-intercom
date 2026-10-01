package com.intercom.video.twoway.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.core.pairing.PairingLink
import com.intercom.video.twoway.data.entity.PairedDevice
import com.intercom.video.twoway.net.network.LocalAddress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PairedDevicesViewModel(private val container: AppContainer) : ViewModel() {
    val devices: StateFlow<List<PairedDevice>> = container.pairedDevices.observeTrusted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The text of this phone's pairing QR code, or null when the phone has no local network address yet. */
    fun myCode(): String? {
        val runtime = container.runtime.value ?: return null
        val host = LocalAddress.find() ?: return null
        val port = runtime.engine.signalingPort.takeIf { it > 0 } ?: return null
        return PairingLink(runtime.self.deviceId, host, port, runtime.identityFingerprint.copyOf(16), runtime.self.name).encode()
    }

    fun rename(deviceId: String, name: String) {
        viewModelScope.launch { container.pairedDevices.rename(deviceId, name) }
    }

    fun remove(deviceId: String) {
        viewModelScope.launch { container.pairedDevices.revoke(deviceId) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PairedDevicesViewModel(container) as T
    }
}
