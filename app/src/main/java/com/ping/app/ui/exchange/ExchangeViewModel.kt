package com.ping.app.ui.exchange

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.ping.app.auth.GestureCamera
import com.ping.app.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    private val gestureCamera: GestureCamera,
) : ViewModel() {

    val cameraState = gestureCamera.state
    val session = NearbyExchangeService.session

    fun startCamera(owner: LifecycleOwner, preview: PreviewView) =
        gestureCamera.start(owner, preview)

    fun stopCamera() = gestureCamera.stop()

    fun resetCamera() = gestureCamera.reset()
}
