package com.ping.app.ui.home

import androidx.lifecycle.ViewModel
import com.ping.app.data.ContactRepository
import com.ping.app.data.ProfileRepository
import com.ping.app.model.Contact
import com.ping.app.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    contactRepository: ContactRepository,
) : ViewModel() {

    val profile: Flow<Profile?> = profileRepository.profile
    val contacts: Flow<List<Contact>> = contactRepository.allContacts
}
