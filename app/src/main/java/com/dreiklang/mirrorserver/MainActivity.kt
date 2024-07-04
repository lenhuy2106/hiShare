package com.dreiklang.mirrorserver

import android.Manifest.permission
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dreiklang.mirrorserver.ui.theme.MirrorServerTheme
import fi.iki.elonen.NanoHTTPD
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStateCallback


class MainActivity : ComponentActivity() {

    companion object {
        private val TAG: String? = MainActivity::class.simpleName
    }

    private var localPeerConnection: PeerConnection? = null
    private var remotePeerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MirrorServerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // logic

        // TODO continue on permission granted callback
        // ask permissions
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(permission.RECORD_AUDIO),
                0);
        }

        // init webserver
        val webserver = object : NanoHTTPD(8080) {
            override fun serve(session: IHTTPSession): Response {
                // return newChunkedResponse(Response.Status.OK, MIME_HTML, assets.open("index.html"))
                val uri = session.uri.removePrefix("/").ifEmpty { "index.html" }
                Log.i(TAG, "Loading $uri")
                try {
                    val mime = when (uri.substringAfterLast(".")) {
                        "ico" -> "image/x-icon"
                        "css" -> "text/css"
                        "js" -> "application/javascript"
                        else -> "text/html"
                    }

                    return newChunkedResponse(
                        Response.Status.OK,
                        mime,
                        assets.open("www/$uri") // prefix with www because your files are not in the root folder in assets
                    )
                } catch (ex: Exception) {
                    val message = "Failed to load asset $uri because $ex"
                    Log.e(TAG, message, ex)
                    return newFixedLengthResponse(message)
                }
            }
        }.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // init peerConnectionFactory globals
        PeerConnectionFactory.initialize(InitializationOptions
            .builder(this)
            .createInitializationOptions())

        val adm = createAudioDeviceModule()
        adm.setSpeakerMute(false)
        adm.setMicrophoneMute(false)

        // create a new PeerConnectionFactory instance
        val peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            // TODO set audio device module
            // https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java?autodive=0%2F%2F
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        start(peerConnectionFactory)
        call(peerConnectionFactory)
    }

    private fun start(peerConnectionFactory: PeerConnectionFactory) {
        // Now create a VideoCapturer instance. Callback methods are there if you want to do something! Duh!
        // TODO use audio capturer
        // val videoCapturerAndroid: VideoCapturer = getVideoCapturer(CustomCameraEventsHandler())

        //Create MediaConstraints - Will be useful for specifying video and audio constraints.
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))

        //create an AudioSource instance
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
        localAudioTrack!!.setEnabled(true)
    }

    private fun call(peerConnectionFactory: PeerConnectionFactory) {
        //we already have video and audio tracks. Now create peerconnections
        // empty iceServerList
        val iceServers: List<IceServer> = ArrayList()

        //create sdpConstraints
        val sdpConstraints = MediaConstraints()
        //sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))

        //creating localPeerConnection
        localPeerConnection = peerConnectionFactory.createPeerConnection(
            iceServers,
            sdpConstraints,
            object : CustomPeerConnectionObserver() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    Log.d(TAG, "ice candidate.")
                    onIceCandidateReceived(localPeerConnection!!, iceCandidate)
                }
            })

        //creating local audiotrack
        localPeerConnection!!.addTrack(localAudioTrack)

        //creating remotePeerConnection
        remotePeerConnection = peerConnectionFactory.createPeerConnection(
            iceServers,
            sdpConstraints,
            object : CustomPeerConnectionObserver() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    onIceCandidateReceived(remotePeerConnection!!, iceCandidate)
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)

                    Log.i(TAG, "play audio stream")
                    //if(mediaStream.audioTracks.size > 0) {
                        val am: AudioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager
                        am.isSpeakerphoneOn = true
                        val remoteAudioTrack = mediaStream.audioTracks[0]
                    //}

                    // gotRemoteStream(mediaStream)
                }
            })

        Log.i(TAG, "#1 create offer session description (sdp).")
        localPeerConnection!!.createOffer(object : CustomSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                super.onCreateSuccess(sessionDescription)

                Log.i(TAG, "#2 set offer as local description: ${sessionDescription.type}: sdp[${sessionDescription.description}]")
                localPeerConnection!!.setLocalDescription(
                    CustomSdpObserver(),
                    sessionDescription
                )


                // TODO signaling (exchange sessiondescription)

                remotePeerConnection!!.setRemoteDescription(
                    CustomSdpObserver(),
                    sessionDescription
                )
                remotePeerConnection!!.createAnswer(object : CustomSdpObserver() {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
                        Log.i(TAG, "remote answer generated. Now set it as local desc for remote peer and remote desc for local peer.")
                        super.onCreateSuccess(sessionDescription)
                        remotePeerConnection!!.setLocalDescription(
                            CustomSdpObserver(),
                            sessionDescription
                        )
                        localPeerConnection!!.setRemoteDescription(
                            CustomSdpObserver(),
                            sessionDescription
                        )
                    }
                }, MediaConstraints())
            }
        }, sdpConstraints)
    }

    private fun gotRemoteStream(stream: MediaStream) {
        //we have remote video stream. add to the renderer.
        val audioTrack: AudioTrack = stream.audioTracks.first()
        runOnUiThread {
            try {
                // TODO play remote audio
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun onIceCandidateReceived(peerConnection: PeerConnection, iceCandidate: IceCandidate?) {
        Log.i(TAG, "we have received ice candidate. We can set it to the other peer.")
        if (peerConnection === localPeerConnection) {
            remotePeerConnection!!.addIceCandidate(iceCandidate)
        } else {
            localPeerConnection!!.addIceCandidate(iceCandidate)
        }
    }

    private fun createAudioDeviceModule(): AudioDeviceModule {
        // Set audio record error callbacks.
        val audioRecordErrorCallback: AudioRecordErrorCallback = object : AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: $errorMessage")
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: AudioRecordStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: $errorCode. $errorMessage")
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordError: $errorMessage")
            }
        }
        val audioTrackErrorCallback: AudioTrackErrorCallback = object : AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: $errorMessage")
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: AudioTrackStartErrorCode, errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: $errorCode. $errorMessage")
            }

            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackError: $errorMessage")
            }
        }

        // Set audio record state callbacks.
        val audioRecordStateCallback: AudioRecordStateCallback = object : AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                Log.i(TAG, "Audio recording starts")
            }

            override fun onWebRtcAudioRecordStop() {
                Log.i(TAG, "Audio recording stops")
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback: AudioTrackStateCallback = object : AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                Log.i(TAG, "Audio playout starts")
            }

            override fun onWebRtcAudioTrackStop() {
                Log.i(TAG, "Audio playout stops")
            }
        }

        return JavaAudioDeviceModule.builder(applicationContext)
            // .setSamplesReadyCallback(saveRecordedAudioToFile)
            // .setUseHardwareAcousticEchoCanceler(true)
            // .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .createAudioDeviceModule()
    }


    @Composable
    fun Greeting(name: String, modifier: Modifier = Modifier) {
        Text(
            text = "Hello $name!",
            modifier = modifier
        )
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        MirrorServerTheme {
            Greeting("Android")
        }
    }
}

open class CustomPeerConnectionObserver: PeerConnection.Observer {

    companion object {
        private val TAG: String? = CustomPeerConnectionObserver::class.simpleName
    }

    override fun onSignalingChange(signalingState: SignalingState) {
        Log.d(
            TAG,
            "onSignalingChange() called with: signalingState = [$signalingState]"
        )
    }

    override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
        Log.d(
            TAG,
            "onIceConnectionChange() called with: iceConnectionState = [$iceConnectionState]"
        )
    }

    override fun onIceConnectionReceivingChange(b: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange() called with: b = [$b]")
    }

    override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
        Log.d(
            TAG,
            "onIceGatheringChange() called with: iceGatheringState = [$iceGatheringState]"
        )
    }

    override fun onIceCandidate(iceCandidate: IceCandidate) {
        Log.d(TAG, "onIceCandidate() called with: iceCandidate = [$iceCandidate]")
    }

    override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
        Log.d(
            TAG,
            "onIceCandidatesRemoved() called with: iceCandidates = [$iceCandidates]"
        )
    }

    override fun onAddStream(mediaStream: MediaStream) {
        Log.d(TAG, "onAddStream() called with: mediaStream = [$mediaStream]")
    }

    override fun onRemoveStream(mediaStream: MediaStream) {
        Log.d(TAG, "onRemoveStream() called with: mediaStream = [$mediaStream]")
    }

    override fun onDataChannel(dataChannel: DataChannel) {
        Log.d(TAG, "onDataChannel() called with: dataChannel = [$dataChannel]")
    }

    override fun onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded() called")
    }
}

open class CustomSdpObserver : SdpObserver {

    companion object {
        private val TAG: String? = CustomSdpObserver::class.simpleName
    }

    override fun onCreateSuccess(sessionDescription: SessionDescription) {
        Log.d(
            TAG,
            "onCreateSuccess() called with: sessionDescription = [${sessionDescription.description}]"
        )
    }

    override fun onSetSuccess() {
        Log.d(TAG, "onSetSuccess() called")
    }

    override fun onCreateFailure(s: String) {
        Log.d(TAG, "onCreateFailure() called with: s = [$s]")
    }

    override fun onSetFailure(s: String) {
        Log.d(TAG, "onSetFailure() called with: s = [$s]")
    }

}