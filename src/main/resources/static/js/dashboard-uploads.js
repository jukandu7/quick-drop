// Dashboard uploads helpers. Loaded before dashboard.js initializes page state.
    async function startUploads(files, retry = false) {
        if (uploadInProgress) {
            uploadStatus.textContent =
                    "Wait for the current uploads to finish.";
            return;
        }

        if (!retry) {
            if (files.length === 0) {
                return;
            }
            if (uploadBatch?.retryableEntries().length && !window.confirm(
                    "Starting a new batch replaces the previous results "
                    + "and pending retries. Continue?"
            )) {
                fileInput.value = "";
                return;
            }
            uploadBatch = new UploadBatch(files, {
                expiration: expirationSelect.value
            });
        }

        const attempts = retry ? uploadBatch?.retryableEntries() || []
                : uploadBatch.entries;
        if (attempts.length === 0) {
            return;
        }

        uploadInProgress = true;
        browseFilesButton.disabled = true;
        expirationSelect.disabled = true;
        retryFailedButton.disabled = true;
        retryFailedButton.hidden = true;
        retryNote.hidden = true;
        setSelectedFiles(attempts.map(entry => entry.file));
        const row = getUploadRow();

        try {
            for (let index = 0; index < attempts.length; index++) {
                const entry = attempts[index];
                const file = entry.file;
                prepareUploadRow(row, file);
                uploadStatus.textContent = (retry ? "Retrying " : "Uploading ")
                        + (index + 1) + " of " + attempts.length + ": " + file.name;

                if (file.size === 0 || file.size > MAX_FILE_SIZE_BYTES) {
                    uploadBatch.finish(entry, {
                        success: false,
                        retryable: false,
                        message: file.size === 0
                                ? "Empty file — choose a file with content."
                                : "Too large — " + MAX_FILE_SIZE_LABEL + " maximum. Choose a smaller file."
                    });
                    continue;
                }

                try {
                    const overwrite = Boolean(findFileLink(file.name));
                    if (overwrite && !window.confirm(
                            "A file named " + file.name
                            + " already exists. Replace it?"
                    )) {
                        uploadBatch.finish(entry, {skipped: true, message: "Skipped — not replaced."});
                        continue;
                    }

                    let outcome = await sendUpload(file, overwrite, row,
                            uploadBatch.options.expiration);
                    if (outcome.requiresOverwrite) {
                        outcome = window.confirm(outcome.message + " Replace it?")
                                ? await sendUpload(file, true, row, uploadBatch.options.expiration)
                                : {skipped: true, message: "Skipped — not replaced."};
                    }
                    uploadBatch.finish(entry, outcome);
                } catch (error) {
                    uploadBatch.finish(entry, {
                        success: false,
                        retryable: true,
                        message: "Upload interrupted. Check whether the file arrived before retrying."
                    });
                }
            }
        } finally {
            finishUploadProgress(row, uploadBatch);
            uploadStatus.textContent = uploadBatch.summary();
            const retryCount = uploadBatch.retryableEntries().length;
            retryFailedButton.textContent = "Retry failed (" + retryCount + ")";
            retryFailedButton.hidden = retryCount === 0;
            retryFailedButton.disabled = false;
            retryNote.hidden = retryCount === 0;
            fileInput.value = "";
            browseFilesButton.disabled = false;
            expirationSelect.disabled = false;
            uploadInProgress = false;
        }
    }

    function setSelectedFiles(files) {
        uploadQueue.replaceChildren();

        if (files.length > 0) {
            const row = document.createElement("div");
            const name = document.createElement("strong");
            const progress = document.createElement("progress");
            const status = document.createElement("small");

            row.className = "upload-item";
            name.className = "upload-item-name";
            progress.max = 100;
            progress.value = 0;
            status.className = "upload-item-status";

            row.appendChild(name);
            row.appendChild(progress);
            row.appendChild(status);
            uploadQueue.appendChild(row);
            prepareUploadRow(row, files[0]);
        }

        uploadStatus.textContent = files.length === 0
                ? "No files selected."
                : files.length + (files.length === 1
                        ? " file selected."
                        : " files selected.");
    }

    function getUploadRow() {
        return uploadQueue.querySelector(".upload-item");
    }

    function prepareUploadRow(row, file) {
        UploadFeedback.prepare(row, file, formatFileSize(file.size));
    }

    function finishUploadProgress(row, batch) {
        UploadFeedback.finish(row, batch, uploadQueue);
    }

    function setRowStatus(row, message) {
        row.querySelector(".upload-item-status").textContent = message;
    }

    function sendUpload(file, overwrite, row, expiration) {
        return new Promise(resolve => {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("expiration", expiration);
            formData.append("overwrite", overwrite);

            const request = new XMLHttpRequest();
            request.open("POST", uploadForm.action);
            request.setRequestHeader("X-CSRF-Token", document.getElementById("csrf-token").content);
            request.responseType = "json";
            setRowStatus(row, "Uploading — 0%");

            request.upload.addEventListener("progress", event => {
                UploadFeedback.progress(row, event);
            });

            request.addEventListener("load", () => {
                const response = request.response;

                if (request.status >= 200 && request.status < 300) {
                    if (!response?.filename || !response?.shareToken) {
                        resolve({success: false, retryable: true,
                            message: "Could not confirm the upload. Check whether the file arrived before retrying."});
                        return;
                    }
                    row.querySelector("progress").value = 100;
                    setRowStatus(row, "Uploaded");
                    storageStatus.textContent = response.storageLabel;
                    addFileToList(
                            response.filename,
                            response.sharePath,
                            response.shareToken,
                            response.expirationLabel
                    );
                    scheduleAvailableFilesSync();
                    queueUploadedFileNotification(response.filename);
                    resolve({success: true});
                    return;
                }

                const message = response?.message ?? "Upload failed.";
                setRowStatus(row, message);
                resolve({
                    success: false,
                    retryable: ![400, 410, 413].includes(request.status),
                    requiresOverwrite: response?.requiresOverwrite,
                    message
                });
            });

            request.addEventListener("error", () => {
                const message = "Connection lost. Check whether the file arrived before retrying.";
                setRowStatus(row, message);
                resolve({success: false, retryable: true, message});
            });

            ["abort", "timeout"].forEach(eventName => {
                request.addEventListener(eventName, () => resolve({
                    success: false,
                    retryable: true,
                    message: "Upload interrupted. Check whether the file arrived before retrying."
                }));
            });

            request.send(formData);
        });
    }

    function formatFileSize(bytes) {
        if (bytes < 1024 * 1024) {
            return (bytes / 1024).toFixed(1) + " KB";
        }

        if (bytes < 1024 * 1024 * 1024) {
            return (bytes / (1024 * 1024)).toFixed(1) + " MB";
        }

        return (bytes / (1024 * 1024 * 1024)).toFixed(1) + " GB";
    }
