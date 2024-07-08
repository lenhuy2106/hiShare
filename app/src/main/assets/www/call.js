const button = document.getElementById("button");
const peerConnection = new RTCPeerConnection();

async function connect() {
    button.innerText = "connecting..."
    button.disabled = true;
    peerConnection.addEventListener("icecandidate", onLocalIceCandidateCreated);
    peerConnection.addEventListener("iceconnectionstatechange", onIceConnectionStateChanged);
    peerConnection.addEventListener("track", onTrackAdded);

    // bidirectional (listen/receive only)
    /*
    const mediaStream = await navigator.mediaDevices.getUserMedia({
        video: false,
        audio: true,
    });
    peerConnection.addStream(mediaStream);
    // vs. only add tracks
    // for (const track of mediaStream.getTracks()) {
    //     peerConnection.addTrack(track);
    // }
    */

    var emptyOffer = await peerConnection.createOffer({ "offerToReceiveAudio": true });
    console.log("local offer desc (ice-candidates-empty sdp) created: ", emptyOffer.sdp)

    // inits ice candidates gathering
    await peerConnection.setLocalDescription(emptyOffer);
}

async function onLocalIceCandidateCreated(ev) {
    const iceCandidate = ev.candidate;
    console.log("local ice candidate created: ", iceCandidate);

    if (event.candidate === null) {
        console.log("ice candidates gathering completed.")
        // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/icecandidate_event#indicating_that_ice_gathering_is_complete

        const completeOffer = peerConnection.localDescription;
        console.log("sending local offer desc...: ", completeOffer)
        const response = await fetch("send_offer", {
          method: "POST",
          body: completeOffer.sdp,
          headers: {}
        })

        // answer desc is promise itself
        const answer_desc = await response.text();
        console.log("response received: ", response)
        console.log("remote answer desc: ", answer_desc);

        await peerConnection.setRemoteDescription({type: "answer", sdp: answer_desc});
        console.log("set remote answer desc success.");
    }
}

async function onIceConnectionStateChanged(ev) {
    console.log("iceconnectionstatechange: ", peerConnection.iceConnectionState);
    button.innerText = peerConnection.iceConnectionState;
}

async function onTrackAdded(ev) {
        console.log("on track negotiated: ", ev.track.kind);
        // play audio in browser
        const stream = new MediaStream([ev.track]);

        // chrome bug workaround: https://stackoverflow.com/questions/24287054/chrome-wont-play-webaudio-getusermedia-via-webrtc-peer-js/55644983#55644983
        var audioObj = document.createElement("AUDIO");
        audioObj.srcObject = stream;
        audioObj = null;

        const audioCtx = new AudioContext();
        const source = audioCtx.createMediaStreamSource(stream);
        source.connect(audioCtx.destination);
}