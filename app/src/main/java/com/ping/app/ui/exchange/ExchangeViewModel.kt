package com.ping.app.ui.exchange

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ping.app.auth.GestureCamera
import com.ping.app.data.ContactRepository
import com.ping.app.model.Contact
import com.ping.app.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    private val gestureCamera: GestureCamera,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    val cameraState = gestureCamera.state
    val surfaceRequest = gestureCamera.surfaceRequest
    val session = NearbyExchangeService.session

    fun startCamera(owner: LifecycleOwner) = gestureCamera.start(owner)

    fun stopCamera() = gestureCamera.stop()

    fun resetCamera() = gestureCamera.reset()

    /** Stores where the user met [contact]; survives the screen closing right after. */
    fun saveMemo(contact: Contact, memo: String) {
        if (memo.isBlank()) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                val current = contactRepository.getById(contact.id) ?: return@withContext
                contactRepository.update(current.copy(memo = memo.trim()))
            }
        }
    }
}
