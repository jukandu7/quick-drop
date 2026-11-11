// Page state, event bindings, and startup. Helpers are loaded first.
    const uploadForm = document.getElementById("upload-form");
    const fileInput = document.getElementById("file");
    const browseFilesButton = document.getElementById("browse-files");
    const dropZone = document.getElementById("drop-zone");
    const uploadQueue = document.getElementById("upload-queue");
    const uploadStatus = document.getElementById("upload-status");
    const retryFailedButton = document.getElementById("retry-failed");
    const retryNote = document.getElementById("retry-note");
    const expirationSelect = document.getElementById("expiration");
    const filesList = document.getElementById("files-list");
    const fileSearch = document.getElementById("file-search");
    const fileSort = document.getElementById("file-sort");
    const fileResults = document.getElementById("file-results");
    const noMatches = document.getElementById("no-matches");
    const emptyMessage = document.getElementById("empty-message");
    const storageStatus = document.getElementById("storage-status");
    let shareBaseUrl = null;
    const connectionStatus = document.getElementById("connection-status");
    const connectionHelp = document.getElementById("connection-help");
    const ownerUnlock = document.getElementById("owner-unlock");
    const ownerAddress = document.getElementById("owner-address");
    const ownerQr = document.getElementById("owner-qr");
    const copyOwnerButton = document.getElementById("copy-owner-link");
    const networkStatus = document.getElementById("network-status");
    const shareNetworkStatus = document.getElementById("share-network-status");
    const shareResult = document.getElementById("share-result");
    const shareQr = document.getElementById("share-qr");
    const shareUrl = document.getElementById("share-url");
    const shareFilesLabel = document.getElementById("share-files-label");
    const copyAgainButton = document.getElementById("copy-again");
    const receiveTools = document.getElementById("receive-tools");
    const receiveStartControls =
            document.getElementById("receive-start-controls");
    const receiveExpiration =
            document.getElementById("receive-expiration");
    const startReceivingButton =
            document.getElementById("start-receiving");
    const receiveStatus = document.getElementById("receive-status");
    const receiveResult = document.getElementById("receive-result");
    const receiveQr = document.getElementById("receive-qr");
    const receiveUrl = document.getElementById("receive-url");
    const receiveExpirationLabel =
            document.getElementById("receive-expiration-label");
    const copyReceiveButton =
            document.getElementById("copy-receive-link");
    const stopReceivingButton =
            document.getElementById("stop-receiving");
    const copyNotification =
            document.getElementById("copy-notification");
    const fileActions = document.getElementById("file-actions");
    const selectionStatus = document.getElementById("selection-status");
    const fileActionStatus = document.getElementById("file-action-status");
    const shareSelectedButton = document.getElementById("share-selected");
    const revokeSelectedButton = document.getElementById("revoke-selected");
    const downloadSelectedButton = document.getElementById("download-selected");
    const selectAllButton = document.getElementById("select-all");
    const deleteSelectedButton =
            document.getElementById("delete-selected");
    const MAX_FILE_SIZE_BYTES = Number(document.getElementById("upload-limit").content);
    const MAX_FILE_SIZE_LABEL = document.getElementById("upload-limit").dataset.label;

    let uploadBatch = null;
    let lastShareUrl = "";
    let lastSharePath = "";
    let lastShareTokens = new Set();
    let fileActionInProgress = false;
    let downloadRequestCount = 0;
    let lastReceiveUrl = receiveTools.dataset.senderUrl || "";
    let receivingActive = receiveTools.dataset.active === "true";
    let receivingPath = lastReceiveUrl ? new URL(lastReceiveUrl).pathname : "";
    let receiveActionInProgress = false;
    let receiveMutationFinishedAt = 0;
    let receiveExpiryTimer;
    let connectionState = "checking";
    let uploadInProgress = false;
    let notificationTimer;
    let uploadedNotificationTimer;
    let uploadedFileNames = [];
    let receivedNotificationTimer;
    let receivedFileNames = [];
    let fileSyncTimer;
    let fileSyncInProgress = false;
    let fileEvents;

    const connection = new QuickDropConnection({
        url: "/api/status",
        onStatus: applyLiveStatus,
        onConnection: showConnectionState
    });

    if ("EventSource" in window) {
        fileEvents = new EventSource("/events/files");
        fileEvents.addEventListener("connected", () => {
            scheduleAvailableFilesSync();
            connection.refresh();
        });
        fileEvents.addEventListener(
                "files-changed",
                scheduleAvailableFilesSync
        );
        fileEvents.addEventListener(
                "file-received",
                event => queueReceivedFileNotification(event.data)
        );
        fileEvents.addEventListener(
                "file-uploaded",
                event => queueUploadedFileNotification(event.data)
        );
    }

    window.setInterval(scheduleAvailableFilesSync, 30000);
    window.setInterval(updateFileExpirationLabels, 30000);

    browseFilesButton.addEventListener("click", () => fileInput.click());
    retryFailedButton.addEventListener("click", () => startUploads([], true));

    startReceivingButton.addEventListener("click", startReceiving);
    copyOwnerButton.addEventListener("click", copyOwnerLink);

    copyReceiveButton.addEventListener("click", async () => {
        if (lastReceiveUrl && connectionState === "connected") {
            try {
                await copyText(lastReceiveUrl);
                receiveStatus.textContent = "Guest upload link copied.";
                showNotification("Guest upload link copied!");
            } catch (error) {
                showNotification("Guest link could not be copied. You can scan its QR code instead.", 5000);
            }
        }
    });

    stopReceivingButton.addEventListener("click", async () => {
        if (!receivingActive || receiveActionInProgress || !window.confirm(
                "Stop guest uploads and disable this guest link? Your own devices will stay connected."
        )) {
            return;
        }

        receiveActionInProgress = true;
        clearTimeout(receiveExpiryTimer);
        updateConnectionTools();

        try {
            const response = await fetch("/receive-links", {
                method: "DELETE",
                headers: {"X-CSRF-Token": document.getElementById("csrf-token").content}
            });
            const result = await response.json();

            if (!response.ok) {
                receiveStatus.textContent = result.message;
                return;
            }

            receivingActive = false;
            receivingPath = "";
            receiveStatus.textContent = "Guest uploads stopped.";
            showNotification("Guest uploads stopped.");
        } catch (error) {
            receiveStatus.textContent =
                    "Guest uploads could not be stopped because the "
                    + "server could not be reached.";
        } finally {
            receiveMutationFinishedAt = Date.now();
            receiveActionInProgress = false;
            updateConnectionTools();
            connection.refresh();
        }
    });

    fileInput.addEventListener("change", () => {
        const files = Array.from(fileInput.files);

        if (files.length > 0) {
            startUploads(files);
        }
    });

    ["dragenter", "dragover"].forEach(eventName => {
        dropZone.addEventListener(eventName, event => {
            event.preventDefault();
            dropZone.classList.add("drag-over");
        });
    });

    ["dragleave", "drop"].forEach(eventName => {
        dropZone.addEventListener(eventName, event => {
            event.preventDefault();
            dropZone.classList.remove("drag-over");
        });
    });

    dropZone.addEventListener("drop", event => {
        if (uploadInProgress) {
            uploadStatus.textContent =
                    "Wait for the current uploads to finish.";
            return;
        }

        const files = Array.from(event.dataTransfer.files);

        if (files.length > 0) {
            startUploads(files);
        }
    });

    dropZone.addEventListener("keydown", event => {
        if (event.key === "Enter" || event.key === " ") {
            event.preventDefault();
            fileInput.click();
        }
    });

    uploadForm.addEventListener("submit", event => {
        event.preventDefault();
    });

    filesList.addEventListener("change", updateFileControls);

    fileSearch.addEventListener("input", updateFileControls);
    fileSort.addEventListener("change", updateFileControls);
    selectAllButton.addEventListener("click", () => {
        if (fileActionInProgress) {
            return;
        }

        const choices = getVisibleFileChoices();
        const allSelected = choices.every(choice => choice.checked);

        choices.forEach(choice => choice.checked = !allSelected);
        updateFileControls();
    });

    shareSelectedButton.addEventListener("click", () => {
        createShareLink(getSelectedFileChoices());
    });

    downloadSelectedButton.addEventListener("click", () => {
        downloadSelectedFiles(getSelectedFileChoices());
    });

    revokeSelectedButton.addEventListener("click", () => {
        revokeDownloadLinks(getSelectedFileChoices());
    });

    deleteSelectedButton.addEventListener("click", () => {
        deleteFiles(getSelectedFileChoices());
    });

    copyAgainButton.addEventListener("click", async () => {
        if (lastShareUrl && shareBaseUrl && connectionState === "connected") {
            try {
                await copyText(lastShareUrl);
                fileActionStatus.textContent = "Share link copied.";
                showNotification();
            } catch (error) {
                fileActionStatus.textContent = "Copying was blocked; scan the QR code or copy the displayed link.";
                showNotification("Share link could not be copied. You can scan its QR code instead.", 5000);
            }
        }
    });

    scheduleAvailableFilesSync();
    updateFileControls();
    connection.start();
    window.addEventListener("pagehide", () => {
        connection.stop();
        clearTimeout(receiveExpiryTimer);
    });
    window.addEventListener("pageshow", event => {
        if (event.persisted) connection.start();
    });
