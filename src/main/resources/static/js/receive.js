    const uploadForm = document.getElementById("receive-upload-form");
    const fileInput = document.getElementById("receive-file");
    const browseButton = document.getElementById("receive-browse-files");
    const dropZone = document.getElementById("receive-drop-zone");
    const uploadQueue = document.getElementById("receive-upload-queue");
    const uploadStatus = document.getElementById("receive-upload-status");
    const retryFailedButton = document.getElementById("receive-retry-failed");
    const retryNote = document.getElementById("receive-retry-note");
    const connectionStatus = document.getElementById("receive-connection-status");
    const connectionHelp = document.getElementById("receive-connection-help");
    const sessionEndedMessage = document.getElementById("receive-session-ended");
    const sessionExpiration = document.getElementById("receive-session-expiration");
    const MAX_FILE_SIZE_BYTES = Number(document.getElementById("upload-limit").content);
    const MAX_FILE_SIZE_LABEL = document.getElementById("upload-limit").dataset.label;
    let uploadInProgress = false;
    let uploadBatch = null;
    let receivingSessionEnded = false;
    let receivingEndedAuthoritatively = false;
    let expiryTimer = null;
    let receivingDeadline = null;

    const connection = new QuickDropConnection({
        url: uploadForm.action.replace(/\/upload\/?$/, "/status"),
        onStatus: (snapshot, timing = {}) => {
            if (receivingEndedAuthoritatively) {
                return;
            }

            if (snapshot.active === false) {
                markReceivingEnded(true);
                return;
            }

            if (snapshot.active !== true) {
                return;
            }

            clearTimeout(expiryTimer);
            receivingDeadline = null;
            receivingSessionEnded = false;
            sessionEndedMessage.hidden = true;

            const serverTime = Date.parse(snapshot.serverTime);
            const expiresAt = Date.parse(snapshot.expiresAt);

            if (Number.isFinite(serverTime) && Number.isFinite(expiresAt)) {
                const requestAge = Number.isFinite(timing.elapsedMs)
                        ? Math.max(0, timing.elapsedMs) : 0;
                const remaining = Math.max(0, expiresAt - serverTime - requestAge);
                // Use the server's remaining time, independent of this device's clock.
                receivingDeadline = performance.now() + remaining;

                if (remaining === 0) {
                    markReceivingEnded(false);
                } else {
                    scheduleExpiryCheck();
                }
            }

            updateReceivingControls();
        },
        onConnection: state => {
            const messages = {
                checking: "Checking connection to Quick Drop…",
                connected: "Connected to Quick Drop.",
                reconnecting: "Connection to Quick Drop lost. Reconnecting…",
                unavailable: "Cannot reach Quick Drop. Still trying…",
                locked: "Quick Drop could not confirm access to this upload link."
            };
            connectionStatus.textContent = messages[state] ?? messages.unavailable;
            connectionHelp.hidden = state === "connected" || state === "checking";
        }
    });

    connection.start();
    window.addEventListener("pagehide", () => {
        connection.stop();
        clearTimeout(expiryTimer);
    });
    window.addEventListener("pageshow", event => {
        if (event.persisted) connection.start();
    });

    function scheduleExpiryCheck() {
        const remaining = receivingDeadline - performance.now();

        if (remaining <= 0) {
            markReceivingEnded(false);
            connection.refresh();
            return;
        }

        expiryTimer = setTimeout(scheduleExpiryCheck,
                Math.min(remaining, 2147483647));
    }

    function updateReceivingControls() {
        browseButton.disabled = uploadInProgress || receivingSessionEnded;
        fileInput.disabled = uploadInProgress || receivingSessionEnded;
        uploadForm.hidden = receivingSessionEnded;
        if (sessionExpiration) sessionExpiration.hidden = receivingSessionEnded;
        const retryCount = uploadBatch?.retryableEntries().length ?? 0;
        retryFailedButton.textContent = "Retry failed (" + retryCount + ")";
        retryFailedButton.disabled = uploadInProgress || receivingSessionEnded;
        retryFailedButton.hidden = uploadInProgress || receivingSessionEnded
                || retryCount === 0;
        retryNote.hidden = retryFailedButton.hidden;
    }

    function markReceivingEnded(authoritative = true) {
        receivingSessionEnded = true;
        receivingEndedAuthoritatively ||= authoritative;
        clearTimeout(expiryTimer);
        sessionEndedMessage.textContent = receivingEndedAuthoritatively
                ? "Upload link is no longer active. Ask the owner for a new upload link."
                : "Upload link may have expired. Checking with Quick Drop…";
        sessionEndedMessage.hidden = false;
        updateReceivingControls();

    }

    function canStartUpload() {
        if (!receivingSessionEnded && receivingDeadline !== null
                && performance.now() >= receivingDeadline) {
            markReceivingEnded(false);
            connection.refresh();
        }

        return !receivingSessionEnded;
    }

    browseButton.addEventListener("click", () => {
        if (!uploadInProgress && canStartUpload()) {
            fileInput.click();
        }
    });
    retryFailedButton.addEventListener("click", () => startUploads([], true));

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
        const files = Array.from(event.dataTransfer.files);

        if (files.length > 0) {
            startUploads(files);
        }
    });

    dropZone.addEventListener("keydown", event => {
        if (!uploadInProgress && canStartUpload()
                && (event.key === "Enter" || event.key === " ")) {
            event.preventDefault();
            fileInput.click();
        }
    });

    uploadForm.addEventListener("submit", event => event.preventDefault());

    async function startUploads(files, retry = false) {
        if (uploadInProgress || !canStartUpload()) {
            return;
        }

        if (!retry) {
            if (files.length === 0) {
                return;
            }

            if (uploadBatch?.retryableEntries().length > 0
                    && !window.confirm("Starting a new batch replaces the "
                            + "previous results and its failed-file retry list. "
                            + "Start the new batch?")) {
                fileInput.value = "";
                return;
            }

            uploadBatch = new UploadBatch(files);
        }

        const attempts = retry
                ? uploadBatch?.retryableEntries() ?? []
                : uploadBatch.entries.slice();

        if (attempts.length === 0) {
            return;
        }

        uploadInProgress = true;
        browseButton.disabled = true;
        fileInput.disabled = true;
        retryFailedButton.hidden = true;
        retryNote.hidden = true;
        let row = null;
        const completed = new Set();

        try {
            createUploadRow(attempts[0].file);
            row = uploadQueue.querySelector(".receive-upload-item");

            for (let index = 0; index < attempts.length; index++) {
                // Let the current request finish, but never start another after expiry.
                if (!canStartUpload()) {
                    break;
                }

                const entry = attempts[index];
                const file = entry.file;
                prepareUploadRow(row, file);
                uploadStatus.textContent = "Sending " + (index + 1)
                        + " of " + attempts.length + ": " + file.name;

                let outcome;

                if (file.size > MAX_FILE_SIZE_BYTES) {
                    outcome = {
                        success: false,
                        retryable: false,
                        message: "Too large — " + MAX_FILE_SIZE_LABEL + " maximum. Choose a smaller file."
                    };
                } else if (file.size === 0) {
                    outcome = {
                        success: false,
                        retryable: false,
                        message: "Empty file — choose a file with content."
                    };
                } else {
                    outcome = await sendUpload(file, row);
                }

                uploadBatch.finish(entry, outcome);
                completed.add(entry);

                if (outcome.expired) {
                    markReceivingEnded(true);
                    break;
                }
            }
        } catch (error) {
            // Finalize every unfinished entry below so the batch remains retryable.
        } finally {
            attempts.filter(entry => !completed.has(entry)).forEach(entry => {
                uploadBatch.finish(entry, {
                    success: false,
                    retryable: !receivingEndedAuthoritatively,
                    message: receivingSessionEnded
                            ? receivingEndedAuthoritatively
                                    ? "Not sent — upload link is no longer active. Ask the owner for a new upload link."
                                    : "Not sent — upload link may have expired. Waiting for Quick Drop to confirm."
                            : "Upload interrupted before confirmation. The file may have arrived; check with the owner before retrying."
                });
            });

            if (receivingEndedAuthoritatively) {
                uploadBatch.retryableEntries().forEach(entry => {
                    uploadBatch.finish(entry, {
                        success: false,
                        retryable: false,
                        message: entry.message + " Upload link is no longer active. Ask the owner for a new upload link."
                    });
                });
            }

            fileInput.value = "";
            uploadInProgress = false;

            if (row) {
                finishUploadProgress(row, uploadBatch);
            }

            uploadStatus.textContent = uploadBatch.summary();
            updateReceivingControls();
        }
    }

    function createUploadRow(file) {
        uploadQueue.replaceChildren();

        const row = document.createElement("div");
        const name = document.createElement("strong");
        const progress = document.createElement("progress");
        const status = document.createElement("small");

        row.className = "receive-upload-item";
        name.className = "receive-upload-item-name";
        progress.max = 100;
        progress.value = 0;
        status.className = "receive-upload-item-status";

        row.appendChild(name);
        row.appendChild(progress);
        row.appendChild(status);
        uploadQueue.appendChild(row);
        prepareUploadRow(row, file);
    }

    function prepareUploadRow(row, file) {
        UploadFeedback.prepare(row, file, formatFileSize(file.size));
    }

    function finishUploadProgress(row, batch) {
        UploadFeedback.finish(row, batch, uploadQueue);
    }

    function sendUpload(file, row) {
        return new Promise(resolve => {
            const formData = new FormData();
            formData.append("file", file);

            const request = new XMLHttpRequest();
            request.open("POST", uploadForm.action);
            request.setRequestHeader("X-CSRF-Token", document.getElementById("csrf-token").content);
            request.responseType = "json";
            setRowStatus(row, "Sending — 0%");

            request.upload.addEventListener("progress", event => {
                UploadFeedback.progress(row, event);
            });

            request.addEventListener("load", () => {
                const response = request.response;
                const responseMessage = typeof response?.message === "string"
                        && response.message.trim()
                        ? response.message
                        : null;

                if (request.status >= 200 && request.status < 300) {
                    if (!responseMessage
                            || typeof response?.filename !== "string"
                            || !response.filename.trim()) {
                        const message = "Quick Drop did not confirm this upload. "
                                + "The file may have arrived; check with the owner before retrying.";
                        setRowStatus(row, message);
                        resolve({success: false, retryable: true, message});
                        return;
                    }

                    row.querySelector("progress").value = 100;
                    setRowStatus(row, responseMessage);
                    resolve({success: true, retryable: false});
                    return;
                }

                const expired = request.status === 410;
                const message = expired
                        ? "Upload link is no longer active. Ask the owner for a new upload link."
                        : responseMessage ?? "Upload failed. Try again.";
                setRowStatus(row, message);
                resolve({
                    success: false,
                    retryable: !expired && request.status !== 400
                            && request.status !== 413,
                    expired,
                    message
                });
            });

            const interrupted = reason => {
                const message = reason + " The file may have arrived; "
                        + "check with the owner before retrying.";
                setRowStatus(row, message);
                resolve({success: false, retryable: true, message});
            };

            request.addEventListener("error", () => {
                interrupted("The connection to Quick Drop failed.");
            });
            request.addEventListener("abort", () => {
                interrupted("The upload was interrupted.");
            });
            request.addEventListener("timeout", () => {
                interrupted("The connection to Quick Drop timed out.");
            });

            request.send(formData);
        });
    }

    function setRowStatus(row, message) {
        row.querySelector(".receive-upload-item-status")
                .textContent = message;
    }

    function formatFileSize(bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        if (bytes < 1024 * 1024) {
            return (bytes / 1024).toFixed(1) + " KB";
        }

        if (bytes < 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024)).toFixed(1) + " MB";
        }

        return (bytes / (1024 * 1024 * 1024)).toFixed(1) + " GB";
    }
