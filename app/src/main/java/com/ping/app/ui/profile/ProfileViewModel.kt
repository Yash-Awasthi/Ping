package com.ping.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val profile: Flow<Profile?> = profileRepository.profile

    fun save(name: String, phone: String, email: String, social: String, note: String) {
        viewModelScope.launch {
            profileRepository.save(
                Profile(
                    displayName = name.trim(),
                    phone = phone.trim(),
                    email = email.trim(),
                    social = social.trim(),
                    note = note.trim(),
                )
            )
        }
    }
}
