package com.ping.app.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.ContactRepository
import com.ping.app.model.Contact
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = ContactDetailViewModel.Factory::class)
class ContactDetailViewModel @AssistedInject constructor(
    private val contactRepository: ContactRepository,
    @Assisted contactId: String,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(contactId: String): ContactDetailViewModel
    }

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact

    init {
        if (contactId.isNotBlank()) {
            viewModelScope.launch { _contact.value = contactRepository.getById(contactId) }
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch { contactRepository.delete(contact) }
    }

    fun save(contact: Contact) {
        viewModelScope.launch {
            contactRepository.update(contact)
            _contact.value = contact
        }
    }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            val updated = contact.copy(isFavorite = !contact.isFavorite)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }
}
