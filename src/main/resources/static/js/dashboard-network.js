// Dashboard network helpers. Loaded before dashboard.js initializes page state.
    function showConnectionState(state) {
        connectionState = state;
        const labels = {
            checking: "Checking connection...",
            connected: "Connected to Quick Drop",
            reconnecting: "Reconnecting to Quick Drop...",
            unavailable: "Quick Drop server unavailable — retrying automatically.",
            locked: "Owner dashboard locked — enter your PIN to reconnect."
        };
        connectionStatus.textContent = labels[state];
        connectionStatus.dataset.state = state;
        connectionHelp.hidden = !["reconnecting", "unavailable"].includes(state);
        connectionHelp.textContent = "Open Quick Drop on the computer storing your files. We’ll reconnect automatically. If its network changed, use the updated address under Connect your own device.";
        document.getElementById("retry-connection").hidden = !["reconnecting", "unavailable"].includes(state);
        ownerUnlock.hidden = state !== "locked";
        updateConnectionTools();
        if (state === "connected") scheduleAvailableFilesSync();
    }

    function setQrUrl(image, url) {
        const source = "/qr?text=" + encodeURIComponent(url);
        if (image.getAttribute("src") !== source
                || (image.complete && image.naturalWidth === 0)) image.src = source;
    }

    function updateConnectionTools() {
        const connected = connectionState === "connected";
        const shareable = connected && Boolean(shareBaseUrl);
        ownerAddress.hidden = !shareable;
        ownerQr.hidden = !shareable;
        copyOwnerButton.disabled = !shareable;
        if (shareBaseUrl) {
            ownerAddress.href = shareBaseUrl;
            ownerAddress.textContent = shareBaseUrl;
            if (shareable) setQrUrl(ownerQr, shareBaseUrl);
        } else {
            ownerAddress.removeAttribute("href");
            ownerAddress.textContent = "";
            ownerQr.removeAttribute("src");
        }
        networkStatus.hidden = shareable;
        networkStatus.textContent = connected
                ? "No local network address is available. Connect the host computer to your local network."
                : "The device link will update when Quick Drop reconnects.";

        lastReceiveUrl = receivingActive && receivingPath && shareBaseUrl
                ? new URL(receivingPath, shareBaseUrl + "/").href : "";
        receiveResult.hidden = !receivingActive;
        receiveStartControls.hidden = receivingActive;
        receiveQr.hidden = !shareable;
        receiveUrl.hidden = !shareable;
        copyReceiveButton.disabled = !shareable || !receivingActive || receiveActionInProgress;
        startReceivingButton.disabled = !shareable || receiveActionInProgress;
        stopReceivingButton.disabled = !connected || receiveActionInProgress;
        if (lastReceiveUrl) {
            receiveUrl.href = lastReceiveUrl;
            receiveUrl.textContent = lastReceiveUrl;
            if (shareable) setQrUrl(receiveQr, lastReceiveUrl);
        } else {
            receiveUrl.removeAttribute("href");
            receiveUrl.textContent = "";
            receiveQr.removeAttribute("src");
        }

        if (lastSharePath && shareBaseUrl) {
            lastShareUrl = new URL(lastSharePath, shareBaseUrl + "/").href;
            shareUrl.href = lastShareUrl;
            shareUrl.textContent = lastShareUrl;
            if (shareable) setQrUrl(shareQr, lastShareUrl);
        }
        shareUrl.hidden = !shareable;
        shareQr.hidden = !shareable;
        shareNetworkStatus.hidden = shareable;
        shareNetworkStatus.textContent = connected
                ? "Connect the host computer to your local network to share this link."
                : "Reconnect to Quick Drop to use this link.";
        copyAgainButton.disabled = !shareable;
        updateFileControls();
    }

    function applyLiveStatus(snapshot, {startedAt, elapsedMs}) {
        const previousAddress = shareBaseUrl;
        shareBaseUrl = snapshot.shareBaseUrl;
        if (!receiveActionInProgress && startedAt >= receiveMutationFinishedAt) {
            clearTimeout(receiveExpiryTimer);
            const wasActive = receivingActive;
            receivingActive = snapshot.receiving.active;
            receivingPath = snapshot.receiving.receivePath || "";
            receiveExpirationLabel.textContent = snapshot.receiving.expirationLabel || "";
            receiveStatus.textContent = receivingActive ? "Upload link is active."
                    : wasActive ? "Upload link is no longer active." : "No active upload link.";
            if (receivingActive) {
                const remaining = Math.max(0, Date.parse(snapshot.receiving.expiresAt)
                        - Date.parse(snapshot.serverTime) - elapsedMs);
                receiveExpiryTimer = setTimeout(() => {
                    receivingActive = false;
                    receivingPath = "";
                    receiveStatus.textContent = "Upload link has expired.";
                    updateConnectionTools();
                    connection.refresh();
                }, remaining);
            }
        }
        updateConnectionTools();
        if (previousAddress && shareBaseUrl && previousAddress !== shareBaseUrl) {
            showNotification("Network changed. Links and QR codes updated.", 5000);
        }
    }

    async function copyOwnerLink() {
        if (connectionState !== "connected" || !shareBaseUrl) return;
        try {
            await copyText(shareBaseUrl);
            showNotification("Device link copied.");
        } catch (error) {
            showNotification("Device link could not be copied. You can scan the QR code instead.", 5000);
        }
    }

    async function startReceiving() {
        if (receiveActionInProgress || connectionState !== "connected" || !shareBaseUrl) return;
        receiveActionInProgress = true;
        clearTimeout(receiveExpiryTimer);
        updateConnectionTools();
        receiveStatus.textContent = "Creating upload link...";

        try {
            const response = await fetch("/receive-links", {
                method: "POST",
                headers: {"Content-Type": "application/json", "X-CSRF-Token": document.getElementById("csrf-token").content},
                body: JSON.stringify({
                    expiration: receiveExpiration.value
                })
            });
            const result = await response.json();

            if (!response.ok) {
                receiveStatus.textContent = result.message;
                return;
            }

            receivingActive = true;
            receivingPath = result.receivePath;
            receiveExpirationLabel.textContent = result.expirationLabel;
            updateConnectionTools();
            receiveStatus.textContent =
                    "Upload link is ready.";
            showNotification("Upload link is ready.");
            if (lastReceiveUrl && connectionState === "connected") {
                try {
                    await copyText(lastReceiveUrl);
                    receiveStatus.textContent = "Upload link copied. Send it to the person who will upload files.";
                } catch (error) {
                    receiveStatus.textContent = "Upload link is ready. Link could not be copied; scan the upload QR code instead.";
                }
            }
            receiveResult.focus({preventScroll: true});
            receiveResult.scrollIntoView({
                block: "nearest"
            });
        } catch (error) {
            receiveStatus.textContent =
                    "The upload link could not be created because the "
                    + "server could not be reached.";
        } finally {
            receiveMutationFinishedAt = Date.now();
            receiveActionInProgress = false;
            updateConnectionTools();
            connection.refresh();
        }
    }

    async function copyText(text) {
        try {
            await navigator.clipboard.writeText(text);
        } catch (error) {
            const temporaryInput = document.createElement("textarea");
            temporaryInput.value = text;
            temporaryInput.style.position = "fixed";
            temporaryInput.style.opacity = "0";
            document.body.appendChild(temporaryInput);
            try {
                temporaryInput.select();
                if (!document.execCommand("copy")) {
                    throw new Error("Clipboard copy failed");
                }
            } finally {
                temporaryInput.remove();
            }
        }
    }
