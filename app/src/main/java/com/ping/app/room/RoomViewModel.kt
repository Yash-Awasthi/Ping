package com.ping.app.room

import android.content.Context
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.ping.app.auth.GestureCamera
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class RoomViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: RoomHub,
    private val gestureCamera: GestureCamera,
) : ViewModel() {

    val state = hub.state
    val cameraState = gestureCamera.state

    fun startCamera(owner: LifecycleOwner, preview: PreviewView) = gestureCamera.start(owner, preview)

    fun stopCamera() = gestureCamera.stop()

    fun open(role: RoomRole, gestureCode: String) {
        RoomHubService.start(context)
        if (role == RoomRole.HOST) hub.host(gestureCode) else hub.join(gestureCode)
    }

    fun addFiles(uris: List<Uri>) = hub.addFiles(uris)

    fun removeShared(localId: String) = hub.removeShared(localId)

    fun decide(endpointId: String, allow: Boolean) = hub.decide(endpointId, allow)

    fun download(globalId: String) = hub.download(globalId)

    fun leave() {
        gestureCamera.stop()
        hub.leave()
        RoomHubService.stop(context)
    }

    override fun onCleared() = leave()
}
