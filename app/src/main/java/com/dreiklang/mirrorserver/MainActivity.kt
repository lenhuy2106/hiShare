package com.dreiklang.mirrorserver

import android.Manifest.permission
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.projection.MediaProjectionManager
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
import kotlinx.coroutines.runBlocking
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
import org.webrtc.Priority
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.WebRTCException
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackErrorCallback
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStartErrorCode
import org.webrtc.audio.JavaAudioDeviceModule.AudioTrackStateCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


/**
 * local ice candidates (to be send) only start to gather after set local desc (eg. from offer)
 */
class MainActivity : ComponentActivity() {

    companion object {
        private val TAG: String? = MainActivity::class.simpleName
        private const val REQ_CODE_PERM_RECORDING = 1
    }

    private var peerConnection: PeerConnection? = null
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

        // TODO continue only on permission granted callback
        // ask permissions
        if (ContextCompat.checkSelfPermission(this, permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(permission.RECORD_AUDIO),
                0);
        }

        // TODO check if android 10
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE_PERM_RECORDING);

        // init webserver
        // multiple routes? https://www.baeldung.com/nanohttpd
        val webserver = object : NanoHTTPD(8080) {
            override fun serve(session: IHTTPSession): Response {
                val uri = session.uri.removePrefix("/").ifEmpty { "index.html" }
                Log.i(TAG, "Loading $uri")

                when (uri) {
                    "send_offer" -> { // incoming call offer
                        try {
                            val postBody: Map<String, String> = HashMap()
                            session.parseBody(postBody)
                            val offer = postBody["postData"]
                            runBlocking {
                                process(offer)
                            }

                            // #4 wait ice candidates gathering...
                            // TODO replace by graceful solution (eg. suspend callback)
                            Thread.sleep(5_000)

                            // #5 get local answer desc (ice-candidate-complete)
                            val answer = peerConnection!!.localDescription.description

                            Log.i(TAG, "sending local answer desc: $answer")
                            return newFixedLengthResponse(answer)

                        } catch (ex: Exception) {
                            val msg = "parse call req failed"
                            Log.e(TAG, msg, ex)
                            return newFixedLengthResponse(msg)
                        }
                    }
                    else -> { // index or assets
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
                                assets.open("www/$uri")
                            )

                        } catch (ex: Exception) {
                            val msg = "Failed to load asset $uri because $ex"
                            Log.e(TAG, msg, ex)
                            return newFixedLengthResponse(msg)
                        }
                    }
                }
            }
        }.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // init peerConnectionFactory globals
        PeerConnectionFactory.initialize(InitializationOptions
            .builder(this)
            .createInitializationOptions())

        val adm = createAudioDeviceModule()
        adm.setSpeakerMute(false)
        adm.setMicrophoneMute(true)

        // create a new PeerConnectionFactory instance
        val peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            // TODO set audio device module
            // https://webrtc.googlesource.com/src/+/refs/heads/main/examples/androidapp/src/org/appspot/apprtc/PeerConnectionClient.java?autodive=0%2F%2F
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        start(peerConnectionFactory)
        // call(peerConnectionFactory)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE_PERM_RECORDING) {
            if (resultCode == RESULT_OK) {
                Log.i(TAG, "screen/audio recording permission granted.")
                val audioCaptureIntent = Intent(this, MediaCaptureService::class.java).apply {
                    action = MediaCaptureService.ACTION_START
                    putExtra(MediaCaptureService.EXTRA_RESULT_DATA, data!!)
                }
                ContextCompat.startForegroundService(this, audioCaptureIntent)
            }
        }
    }

    private fun start(peerConnectionFactory: PeerConnectionFactory) {
        // empty iceServerList
        val iceServers: List<IceServer> = ArrayList()

        //create sdpConstraints
        val sdpConstraints = MediaConstraints()
        //sdpConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))

        //creating localPeerConnection
        peerConnection = peerConnectionFactory.createPeerConnection(
            iceServers,
            sdpConstraints,
            object : CustomPeerConnectionObserver() {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    Log.d(TAG, "local ice candidate created. preparing to deliver...")
                    peerConnection!!.addIceCandidate(iceCandidate)
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    Log.i(TAG, "play remote audio from stream")
                    val am: AudioManager = applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager
                    am.isSpeakerphoneOn = true
                    val remoteAudioTrack = mediaStream.audioTracks[0]
                }
            })

        // Now create a VideoCapturer instance. Callback methods are there if you want to do something! Duh!
        // TODO use audio capturer
        // val videoCapturerAndroid: VideoCapturer = getVideoCapturer(CustomCameraEventsHandler())

        //Create MediaConstraints - Will be useful for specifying video and audio constraints.
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))

        Log.i(TAG, "create audio source track")
        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
        localAudioTrack!!.setEnabled(true)
        peerConnection!!.addTrack(localAudioTrack)

        //  Configuring jitter buffer size
        peerConnection!!.senders.forEach { sender ->
            sender.parameters.encodings.forEach { encoding ->
                Log.i(TAG, "optimizing sender encoding for: $sender")
                // encoding.maxBitrateBps = 500000; // Set max bitrate to ensure quality
                // encoding.minBitrateBps = 300000; // Set min bitrate to ensure stability
                encoding.bitratePriority = 1.0 // High priority for bitrate
                encoding.networkPriority = Priority.HIGH // High priority for network
                encoding.adaptiveAudioPacketTime = true
            }
        }
    }

    private fun call(peerConnectionFactory: PeerConnectionFactory) {
        //we already have video and audio tracks. Now create peerconnections


        /*
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
        */

        /*
        Log.i(TAG, "#1 create offer session description (sdp).")
        peerConnection!!.createOffer(object : CustomSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                super.onCreateSuccess(sessionDescription)

                Log.i(TAG, "#2 set offer as local description: ${sessionDescription.type}: sdp[${sessionDescription.description}]")
                peerConnection!!.setLocalDescription(
                    CustomSdpObserver(),
                    sessionDescription
                )
            }
        }, sdpConstraints)
        */
    }

    /**
     * suspend until local answer desc creation callback
     */
    private suspend fun process(offer: String?) = suspendCoroutine<String> { cont ->
        Log.i(TAG, "call offer incoming: sdp[$offer]")

        // #1 set as remote offer desc
        peerConnection!!.setRemoteDescription(
            object: CustomSdpObserver() {
                override fun onSetSuccess() {
                    super.onSetSuccess()

                    // #2 create local answer desc (ice-candidate-empty)
                    peerConnection!!.createAnswer(object: CustomSdpObserver() {
                        override fun onCreateSuccess(sessionDescription: SessionDescription) {
                            super.onCreateSuccess(sessionDescription)
                            Log.i(TAG, "empty answer created: ${sessionDescription.type}: sdp[${sessionDescription.description}]")

                            // #3 set local answer desc (inits ice-gathering)
                            peerConnection!!.setLocalDescription(object: CustomSdpObserver() {
                                override fun onSetSuccess() {
                                    super.onSetSuccess()
                                    cont.resume(sessionDescription.description)
                                }
                            }, sessionDescription)
                        }

                        override fun onCreateFailure(s: String) {
                            super.onCreateFailure(s)
                            cont.resumeWithException(WebRTCException("create answer desc failed."))
                        }
                    }, MediaConstraints())
                }

                override fun onSetFailure(s: String) {
                    super.onSetFailure(s)
                    cont.resumeWithException(WebRTCException("set as remote desc failed."))
                }
            },
            // offer sdp misses newline at end (truncated)
            SessionDescription(SessionDescription.Type.OFFER, offer + "\n")
        )
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