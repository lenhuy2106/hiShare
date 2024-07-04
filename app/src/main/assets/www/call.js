
async function call() {
    const peerConnection = new RTCPeerConnection();
    var offer = await peerConnection.createOffer();
    await peerConnection.setLocalDescription(offer);
    
    console.log("offer description (sdp) created.")
    console.log(offer)

    // ajax call: await answer description (sdp)

    fetch("fetch_test.html", {
      method: "POST",
      body: JSON.stringify({
        offer: offer,
      }),
      headers: {
        "Content-type": "application/json; charset=UTF-8"
      }
    })
      .then((response) => response.json())
      .then((json) => console.log(json));
}

call()