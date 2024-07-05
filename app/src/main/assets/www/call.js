
async function call() {
    const peerConnection = new RTCPeerConnection();
    var offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);

    console.log("offer description (sdp) created.")
    console.log(offer)

    // ajax call: await answer description (sdp)

    fetch("send_offer", {
      method: "POST",
      body: offer.sdp,
      headers: {}
    })
      .then((response) => console.log(response));
      // .then((json) => console.log(json));
}

call()