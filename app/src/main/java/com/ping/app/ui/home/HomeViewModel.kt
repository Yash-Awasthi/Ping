package com.ping.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.ContactRepository
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    contactRepository: ContactRepository,
) : ViewModel() {

    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val contactCount: StateFlow<Int> = contactRepository.contacts
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
