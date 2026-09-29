package com.ping.app.ui.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.data.ContactRepository
import com.ping.app.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contactId: String = savedStateHandle["contactId"] ?: ""

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact

    init {
        if (contactId.isNotBlank()) {
            viewModelScope.launch { _contact.value = contactRepository.getById(contactId) }
        }
    }

    fun loadContact(id: String) {
        viewModelScope.launch { _contact.value = contactRepository.getById(id) }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch { contactRepository.delete(contact) }
    }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            val updated = contact.copy(isFavorite = !contact.isFavorite)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }
}
