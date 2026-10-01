package com.intercom.video.twoway.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import com.intercom.video.twoway.service.PeerAddress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val entries: StateFlow<List<CallHistoryEntry>> = container.history.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Calls the person back if their device is currently visible on the network. Returns false otherwise. */
    fun callBack(entry: CallHistoryEntry): Boolean {
        val runtime = container.runtime.value ?: return false
        val device = runtime.registry.get(entry.peerDeviceId) ?: return false
        runtime.engine.call(PeerAddress(device.deviceId, device.displayName, device.host, device.port))
        return true
    }

    fun clear() {
        viewModelScope.launch { container.history.clear() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(container) as T
    }
}
