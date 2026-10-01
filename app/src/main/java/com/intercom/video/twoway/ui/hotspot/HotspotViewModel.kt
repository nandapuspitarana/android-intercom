package com.intercom.video.twoway.ui.hotspot

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.network.HotspotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HotspotViewModel(private val container: AppContainer) : ViewModel() {
    val mode: StateFlow<NetworkMode> = container.runtime
        .flatMapLatest { it?.networkMode ?: flowOf(NetworkMode.NONE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkMode.NONE)

    val state: StateFlow<HotspotState> = container.hotspot.state
    val isSupported: Boolean get() = container.hotspot.isSupported

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission

    fun refreshPermission() {
        _hasPermission.value = checkPermission()
    }

    fun start() = container.hotspot.start()
    fun stop() = container.hotspot.stop()

    private fun checkPermission() = ContextCompat.checkSelfPermission(container.appContext, hotspotPermission()) == PackageManager.PERMISSION_GRANTED

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HotspotViewModel(container) as T
    }
}
