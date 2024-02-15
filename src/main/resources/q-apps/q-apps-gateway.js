console.log("Gateway mode");

function qdnGatewayShowModal(message) {
    const modalElementId = "qdnGatewayModal";

    if (document.getElementById(modalElementId) != null) {
        document.body.removeChild(document.getElementById(modalElementId));
    }

    var modalElement = document.createElement('div');
    modalElement.style.cssText = 'position:fixed; z-index:99999; background:#fff; padding:20px; border-radius:5px; font-family:sans-serif; bottom:20px; right:20px; color:#000; max-width:400px; box-shadow:0 3px 10px rgb(0 0 0 / 0.2); font-family:arial; font-weight:normal; font-size:16px;';
    modalElement.innerHTML = message + "<br /><br />";
    modalElement.id = modalElementId;

    var closeButton = document.createElement('button');
    closeButton.style.cssText = 'background-color:#008CBA; border:none; color:white; cursor:pointer; float: right; margin: 10px; padding:15px; border-radius:5px; display:inline-block; text-align:center; text-decoration:none; font-family:arial; font-weight:normal; font-size:16px;';
    closeButton.innerText = "Close";
    closeButton.addEventListener ("click", function() {
        document.body.removeChild(document.getElementById(modalElementId));
    });
    modalElement.appendChild(closeButton);

    var qortalButton = document.createElement('button');
    qortalButton.style.cssText = 'background-color:#4CAF50; border:none; color:white; cursor:pointer; float: right; margin: 10px; padding:15px; border-radius:5px; text-align:center; text-decoration:none; display:inline-block; font-family:arial; font-weight:normal; font-size:16px;';
    qortalButton.innerText = "Learn more";
    qortalButton.addEventListener ("click", function() {
        document.body.removeChild(document.getElementById(modalElementId));
        window.open("https://qortal.org");
    });
    modalElement.appendChild(qortalButton);

    document.body.appendChild(modalElement);
}

window.addEventListener("message", (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
        return;
    }
    if (event.data.action == null || event.data.requestedHandler == null) {
        return;
    }
    if (event.data.requestedHandler != "UI") {
        // Gateway mode only cares about requests that were intended for the UI
        return;
    }

    let response;
    let data = event.data;

    switch (data.action) {
        case "GET_USER_ACCOUNT":
        case "PUBLISH_QDN_RESOURCE":
        case "PUBLISH_MULTIPLE_QDN_RESOURCES":
        case "SEND_CHAT_MESSAGE":
        case "JOIN_GROUP":
        case "DEPLOY_AT":
        case "GET_WALLET_BALANCE":
        case "SEND_COIN":
        case "GET_LIST_ITEMS":
        case "ADD_LIST_ITEMS":
        case "DELETE_LIST_ITEM":
            const errorString = "Interactive features were requested, but these are not yet supported when viewing via a gateway. To use interactive features, please access using the Qortal UI desktop app. More info at: https://qortal.org";
            response = "{\"error\": \"" + errorString + "\"}"

            const modalText = "This app is powered by the Qortal blockchain. You are viewing in read-only mode. To use interactive features, please access using the Qortal UI desktop app.";
            qdnGatewayShowModal(modalText);
            break;

        default:
            console.log('Unhandled gateway message: ' + JSON.stringify(data));
            return;
    }

    handleResponse(event, response);

}, false);