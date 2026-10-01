package com.intercom.video.twoway.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setListenInBackground(v: Boolean) = viewModelScope.launch { container.settings.setListenInBackground(v) }
    fun setStartOnBoot(v: Boolean) = viewModelScope.launch { container.settings.setStartOnBoot(v) }
    fun setAutoRejectUnknown(v: Boolean) = viewModelScope.launch { container.settings.setAutoRejectUnknown(v) }
    fun setLanguage(language: String) {
        com.intercom.video.twoway.ui.LocaleHelper.save(container.appContext, language)
        viewModelScope.launch { container.settings.setLanguage(language) }
    }

    fun setRingtone(uri: String?) = viewModelScope.launch { container.settings.setRingtoneUri(uri) }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
    }
}
