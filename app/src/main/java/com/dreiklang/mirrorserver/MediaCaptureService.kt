package com.dreiklang.mirrorserver
/*
 * Copyright (c) 2020 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * Notwithstanding the foregoing, you may not use, copy, modify, merge, publish,
 * distribute, sublicense, create a derivative work, and/or sell copies of the
 * Software in any work that is designed, intended, or marketed for pedagogical or
 * instructional purposes related to programming, coding, application development,
 * or information technology.  Permission for such use, copying, modification,
 * merger, publication, distribution, sublicensing, creation of derivative works,
 * or sale is expressly withheld.
 *
 * This project and source code may use libraries or frameworks that are
 * released under various Open-Source licenses. Use of those libraries and
 * frameworks are governed by their own individual licenses.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
import java.io.InputStream
import java.security.KeyStore
import java.util.*
import javax.net.ssl.KeyManagerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.experimental.and


class MediaCaptureService : Service() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null

    private lateinit var audioCaptureThread: Thread
    private var audioRecord: AudioRecord? = null

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created.")
        createNotificationChannel()
        startForeground(SERVICE_ID, NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).build())

        mediaProjectionManager = applicationContext.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Audio Capture Service Channel", NotificationManager.IMPORTANCE_DEFAULT)

        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "service start command. intent action: ${intent?.action} ")
        // 1
        return if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, intent.getParcelableExtra(
                        EXTRA_RESULT_DATA
                    )!!) as MediaProjection

                    startAudioCapture()
                    Service.START_STICKY
                }
                ACTION_STOP -> {
                    stopAudioCapture()
                    Service.START_NOT_STICKY
                }
                else -> throw IllegalArgumentException("Unexpected action received: ${intent.action}")
            }
        } else {
            Service.START_NOT_STICKY
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        Log.i(TAG, "starting audio capture...")
//        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
//            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
//            .build()
//
//        val audioFormat = AudioFormat.Builder()
//            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//            .setSampleRate(8000)
//            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
//            .build()
//
//        audioRecord = AudioRecord.Builder()
//            .setAudioFormat(audioFormat)
//            .setBufferSizeInBytes(BUFFER_SIZE_IN_BYTES)
//            .setAudioPlaybackCaptureConfig(config)
//            .build()
//
//        audioRecord!!.startRecording()

        // TODO audiorecord to webrtc track
        init()

        // audioCaptureThread = thread(start = true) {
        // }
    }


    private fun start(peerConnectionFactory: PeerConnectionFactory) {
        // empty iceServerList
        val iceServers: List<IceServer> = ArrayList()
        // val iceServers: List<IceServer> = arrayListOf(PeerConnection.IceServer("stun:stun3.l.google.com:3478"))

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

    private fun createAudioDeviceModule(mediaProjection: MediaProjection): AudioDeviceModule {
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
            .setMediaProjection(mediaProjection)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .createAudioDeviceModule()
    }

    private fun stopAudioCapture() {
        requireNotNull(mediaProjection) { "Tried to stop audio capture, but there was no ongoing capture in place!" }

        audioCaptureThread.interrupt()
        audioCaptureThread.join()

        audioRecord!!.stop()
        audioRecord!!.release()
        audioRecord = null

        mediaProjection!!.stop()
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder? = null

    private fun ShortArray.toByteArray(): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            bytes[i * 2] = (this[i] and 0x00FF).toByte()
            bytes[i * 2 + 1] = (this[i].toInt() shr 8).toByte()
            this[i] = 0
        }
        return bytes
    }


    private fun init() {
        Log.i(TAG, "init sharing...")

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
        }
        Log.i(TAG, "ssl: loading keystore...")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val keyStoreStream: InputStream = assets.open("keystore1.bks")
        keyStore.load(keyStoreStream, "schneeball".toCharArray())
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "schneeball".toCharArray())
        Log.i(TAG, "ssl: keystore loaded...: size ${keyStore.size()}")

        webserver.makeSecure(NanoHTTPD.makeSSLSocketFactory(keyStore, keyManagerFactory), null)
        webserver.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        // init peerConnectionFactory globals
        PeerConnectionFactory.initialize(
            InitializationOptions
                .builder(this)
                .createInitializationOptions()
        )

        val adm = createAudioDeviceModule(mediaProjection!!)
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
        // call(peerConnectionFactory)
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val SERVICE_ID = 123
        private const val NOTIFICATION_CHANNEL_ID = "AudioCapture channel"

        private const val NUM_SAMPLES_PER_READ = 1024
        private const val BYTES_PER_SAMPLE = 2 // 2 bytes since we hardcoded the PCM 16-bit format
        private const val BUFFER_SIZE_IN_BYTES = NUM_SAMPLES_PER_READ * BYTES_PER_SAMPLE

        const val ACTION_START = "AudioCaptureService:Start"
        const val ACTION_STOP = "AudioCaptureService:Stop"
        const val EXTRA_RESULT_DATA = "AudioCaptureService:Extra:ResultData"
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