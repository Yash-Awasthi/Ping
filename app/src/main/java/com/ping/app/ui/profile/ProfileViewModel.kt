package com.ping.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(name: String, phone: String, email: String, social: String, note: String) {
        viewModelScope.launch {
            profileRepository.save(
                Profile(
                    displayName = name,
                    phone = phone,
                    email = email,
                    social = social,
                    note = note,
                )
            )
        }
    }
}
