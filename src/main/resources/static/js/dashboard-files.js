// Dashboard files helpers. Loaded before dashboard.js initializes page state.
    function showNotification(
            message = "Share link copied!",
            duration = 2000
    ) {
        clearTimeout(notificationTimer);
        copyNotification.textContent = message;
        copyNotification.classList.add("show");

        notificationTimer = setTimeout(() => {
            copyNotification.classList.remove("show");
        }, duration);
    }

    function queueReceivedFileNotification(filename) {
        receivedFileNames.push(filename);
        clearTimeout(receivedNotificationTimer);

        receivedNotificationTimer = setTimeout(() => {
            const filenames = receivedFileNames;
            receivedFileNames = [];
            showFileActionNotification("Received", filenames, 6000);
        }, 600);
    }

    function queueUploadedFileNotification(filename) {
        uploadedFileNames.push(filename);
        clearTimeout(uploadedNotificationTimer);

        uploadedNotificationTimer = setTimeout(() => {
            const filenames = uploadedFileNames;
            uploadedFileNames = [];
            showFileActionNotification("Uploaded", filenames, 5000);
        }, 600);
    }

    function showFileActionNotification(action, filenames, duration) {
        const uniqueNames = Array.from(new Set(filenames));

        if (uniqueNames.length === 0) {
            return;
        }

        if (uniqueNames.length === 1) {
            showNotification(
                    action + ' "' + uniqueNames[0] + '".',
                    duration
            );
            return;
        }

        const displayedNames = uniqueNames
                .slice(0, 3)
                .map(name => '"' + name + '"')
                .join(", ");
        const remainingCount = uniqueNames.length - 3;
        const remainingText = remainingCount > 0
                ? ", and " + remainingCount + " more"
                : "";

        showNotification(
                action + " " + uniqueNames.length + " files: "
                + displayedNames + remainingText + ".",
                duration
        );
    }

    function getFileChoices() {
        return Array.from(
                filesList.querySelectorAll(".file-choice")
        );
    }

    function getSelectedFileChoices() {
        return getVisibleFileChoices().filter(choice => choice.checked);
    }

    function getVisibleFileChoices() {
        return getFileChoices().filter(choice => !choice.closest("li").hidden);
    }

    function applyFileFilter() {
        const query = fileSearch.value.trim().toLocaleLowerCase();
        const choices = getFileChoices();
        choices.forEach(choice => {
            const row = choice.closest("li");
            row.hidden = !choice.dataset.filename.toLocaleLowerCase().includes(query);
            if (row.hidden) choice.checked = false;
            row.classList.toggle("is-selected", choice.checked);
        });
        choices.sort((a, b) => {
            if (fileSort.value === "expiration") {
                const expiry = choice => Date.parse(choice.closest("li").dataset.expiresAt) || Infinity;
                const difference = expiry(a) - expiry(b);
                if (difference && !Number.isNaN(difference)) return difference;
            }
            const order = a.dataset.filename.localeCompare(b.dataset.filename, undefined, {numeric: true, sensitivity: "base"});
            return fileSort.value === "name-desc" ? -order : order;
        }).forEach(choice => filesList.appendChild(choice.closest("li")));
        const visible = getVisibleFileChoices().length;
        fileResults.textContent = visible + " of " + choices.length + " files";
        noMatches.hidden = choices.length === 0 || visible !== 0;
    }

    function updateFileControls() {
        applyFileFilter();
        const choices = getVisibleFileChoices();
        const selectedCount = choices
                .filter(choice => choice.checked).length;

        fileActions.hidden = getFileChoices().length === 0;
        emptyMessage.hidden = getFileChoices().length !== 0;
        selectionStatus.textContent = selectedCount + " selected";
        shareSelectedButton.disabled = fileActionInProgress
                || selectedCount === 0 || connectionState !== "connected" || !shareBaseUrl;
        downloadSelectedButton.disabled = fileActionInProgress
                || selectedCount === 0;
        deleteSelectedButton.disabled = fileActionInProgress
                || selectedCount === 0;
        revokeSelectedButton.disabled = fileActionInProgress || selectedCount === 0;
        selectAllButton.disabled = fileActionInProgress || choices.length === 0;
        fileSearch.disabled = fileActionInProgress;
        fileSort.disabled = fileActionInProgress;
        choices.forEach(choice => choice.disabled = fileActionInProgress);
        selectAllButton.textContent = choices.length > 0
                && choices.every(choice => choice.checked)
                ? "Clear selection"
                : "Select all shown";
    }

    function downloadSelectedFiles(choices) {
        if (fileActionInProgress || choices.length === 0) {
            return;
        }

        // A native download streams to disk instead of buffering a large Blob.
        // Each request has its own target so another click won't interrupt it.
        const target = document.createElement("iframe");
        target.name = "download-" + (++downloadRequestCount);
        target.title = "Selected file download";
        target.hidden = true;
        target.addEventListener("load", () => {
            const page = target.contentDocument;
            if (!page || page.URL === "about:blank") {
                return;
            }

            // Attachments go to the browser; only an error page loads here.
            let message = "Download could not start. Check your connection and try again.";
            if (page.contentType === "text/plain") {
                message = page.body.textContent.trim() || message;
            } else if (page.contentType === "application/json") {
                try {
                    message = JSON.parse(page.body.textContent).message || message;
                } catch (error) {
                    // Keep the friendly fallback if the response is not valid JSON.
                }
            }
            fileActionStatus.textContent = message;
            showNotification(message, 6000);
            syncAvailableFiles();
            target.remove();
        });
        document.body.appendChild(target);

        const form = document.createElement("form");
        form.method = "post";
        form.action = "/download/selected";
        form.target = target.name;
        const csrfInput = document.createElement("input");
        csrfInput.type = "hidden";
        csrfInput.name = "_csrf";
        csrfInput.value = document.getElementById("csrf-token").content;
        form.appendChild(csrfInput);
        form.hidden = true;
        choices.forEach(choice => {
            const input = document.createElement("input");
            input.type = "hidden";
            input.name = "tokens";
            input.value = choice.value;
            form.appendChild(input);
        });
        document.body.appendChild(form);
        form.submit();
        form.remove();

        const message = choices.length === 1
                ? "Download requested. Check your browser's downloads."
                : "ZIP download requested for " + choices.length
                        + " files. Check your browser's downloads.";
        fileActionStatus.textContent = message;
        showNotification(message, 4000);
    }

    async function createShareLink(choices) {
        if (fileActionInProgress || choices.length === 0
                || connectionState !== "connected" || !shareBaseUrl) {
            return;
        }

        const tokens = choices.map(choice => choice.value);
        const filenames = choices.map(choice => choice.dataset.filename);
        let sharePath = choices[0].dataset.sharePath;
        let sharedFileCount = 1;
        fileActionInProgress = true;
        updateFileControls();
        fileActionStatus.textContent = "Creating share link...";

        try {
            if (choices.length > 1) {
                const response = await fetch("/share/bundles", {
                    method: "POST",
                    headers: {"Content-Type": "application/json", "X-CSRF-Token": document.getElementById("csrf-token").content},
                    body: JSON.stringify({tokens})
                });
                const result = await response.json();

                if (!response.ok) {
                    fileActionStatus.textContent = result.message;
                    scheduleAvailableFilesSync();
                    return;
                }

                sharePath = result.sharePath;
                sharedFileCount = result.fileCount;
            }

            if (!shareBaseUrl || connectionState !== "connected") {
                fileActionStatus.textContent = "Reconnect to Quick Drop, then create the share link again.";
                return;
            }
            const shareLink = new URL(sharePath, shareBaseUrl + "/").href;
            const availableTokens = new Set(
                    getFileChoices().map(choice => choice.value)
            );
            if (tokens.some(token => !availableTokens.has(token))) {
                fileActionStatus.textContent =
                        "The selected files changed. Check your selection "
                        + "and create a new link.";
                scheduleAvailableFilesSync();
                return;
            }
            showShareResult(shareLink, sharedFileCount);
            lastShareTokens = new Set(tokens);
            shareFilesLabel.textContent = filenames.length === 1
                    ? "File: " + filenames[0]
                    : "Link for " + sharedFileCount + " selected files.";
            try {
                await copyText(shareLink);
            } catch (error) {
                fileActionStatus.textContent = "Share link created. Copying was blocked; scan the QR code or copy the displayed link.";
                showNotification("Share link created. Scan the QR code to use it.", 5000);
                return;
            }
            showNotification(
                    sharedFileCount === 1
                            ? "Share link created and copied!"
                            : "Share link for " + sharedFileCount
                                    + " files created and copied!",
                    4000
            );

            fileActionStatus.textContent = sharedFileCount === 1
                    ? "File link copied."
                    : "One link for " + sharedFileCount + " files copied.";
        } catch (error) {
            fileActionStatus.textContent =
                    "The share link could not be created. Please try again.";
        } finally {
            fileActionInProgress = false;
            updateFileControls();
        }
    }

    function showShareResult(url, fileCount) {
        lastSharePath = new URL(url).pathname;
        lastShareUrl = url;
        shareUrl.href = url;
        shareUrl.textContent = url;
        shareQr.src = "/qr?text=" + encodeURIComponent(url);
        shareQr.alt = fileCount === 1
                ? "QR code for one shared file"
                : "QR code for " + fileCount + " shared files";
        shareResult.hidden = false;
        updateConnectionTools();
        shareResult.scrollIntoView({behavior: "smooth", block: "nearest"});
    }

    function scheduleAvailableFilesSync() {
        clearTimeout(fileSyncTimer);
        fileSyncTimer = setTimeout(syncAvailableFiles, 100);
    }

    async function syncAvailableFiles() {
        if (fileSyncInProgress) {
            return;
        }

        fileSyncInProgress = true;

        try {
            const response = await fetch("/api/files", {cache: "no-store"});

            if (!response.ok) {
                return;
            }

            const snapshot = await response.json();
            const activeTokens = new Set();

            snapshot.files.forEach(file => {
                activeTokens.add(file.shareToken);
                addFileToList(
                        file.filename,
                        "/share/" + file.shareToken,
                        file.shareToken,
                        file.expirationLabel, file.sizeLabel, file.expiresAt
                );
            });

            Array.from(
                    filesList.querySelectorAll("li[data-share-token]")
            ).forEach(item => {
                if (!activeTokens.has(item.dataset.shareToken)) {
                    removeFileFromPage(item.dataset.shareToken);
                }
            });

            storageStatus.textContent = snapshot.storageLabel;
            updateFileControls();
        } catch (error) {
            // EventSource reconnects automatically if the server restarts.
        } finally {
            fileSyncInProgress = false;
        }
    }

    function findFileLink(filename) {
        return Array.from(filesList.querySelectorAll("a[data-filename]"))
                .find(link => link.dataset.filename === filename);
    }

    function addFileToList(
            filename,
            sharePath,
            shareToken,
            expirationLabel,
            sizeLabel,
            expiresAt
    ) {
        const existingLink = findFileLink(filename);

        if (existingLink) {
            const existingItem = existingLink.closest("li");
            const existingChoice = existingItem.querySelector(".file-choice");

            if (existingChoice.value !== shareToken) {
                existingChoice.checked = false;
                invalidateShareResult(existingChoice.value);
            }

            existingItem.dataset.shareToken = shareToken;
            if (expiresAt !== undefined) existingItem.dataset.expiresAt = expiresAt || "";
            if (sizeLabel !== undefined) existingItem.querySelector(".file-size").textContent = sizeLabel;
            existingLink.href = "/download?filename="
                    + encodeURIComponent(filename);

            existingChoice.value = shareToken;
            existingChoice.dataset.sharePath = sharePath;

            existingItem.querySelector(".expiration")
                    .textContent = expirationLabel;
            updateFileControls();
            return;
        }

        const listItem = document.createElement("li");
        const checkbox = document.createElement("input");
        const details = document.createElement("span");
        const link = document.createElement("a");
        const expirationText = document.createElement("small");
        const sizeText = document.createElement("small");
        sizeText.className = "file-size";
        sizeText.textContent = sizeLabel || "Checking size…";
        listItem.dataset.expiresAt = expiresAt || "";

        listItem.dataset.shareToken = shareToken;
        checkbox.type = "checkbox";
        checkbox.className = "file-choice";
        checkbox.value = shareToken;
        checkbox.dataset.filename = filename;
        checkbox.dataset.sharePath = sharePath;
        checkbox.setAttribute("aria-label", "Select " + filename);
        details.className = "file-details";
        link.href = "/download?filename=" + encodeURIComponent(filename);
        link.dataset.filename = filename;
        link.textContent = filename;
        expirationText.className = "expiration";
        expirationText.textContent = expirationLabel;

        details.appendChild(link);
        details.appendChild(sizeText);
        details.appendChild(expirationText);
        listItem.appendChild(checkbox);
        listItem.appendChild(details);
        filesList.appendChild(listItem);

        updateFileControls();
    }

    async function revokeDownloadLinks(choices) {
        if (fileActionInProgress || choices.length === 0) return;
        if (!window.confirm("Revoke existing download links for these files? Any combined links containing them will also stop working. The files will be kept.")) return;
        const tokens = choices.map(choice => choice.value);
        fileActionInProgress = true;
        updateFileControls();
        fileActionStatus.textContent = "Revoking download links...";
        try {
            const response = await fetch("/share-links", {
                method: "DELETE",
                headers: {"Content-Type": "application/json", "X-CSRF-Token": document.getElementById("csrf-token").content},
                body: JSON.stringify({tokens})
            });
            const result = await response.json();
            fileActionStatus.textContent = result.message;
            if (response.ok) {
                result.revokedTokens.forEach(invalidateShareResult);
                scheduleAvailableFilesSync();
            }
        } catch (error) {
            fileActionStatus.textContent = "Could not confirm revocation. Reconnect and refresh the file list before trying again.";
        } finally {
            fileActionInProgress = false;
            updateFileControls();
        }
    }

    async function deleteFiles(choices) {
        if (fileActionInProgress || choices.length === 0) {
            return;
        }

        const tokens = choices.map(choice => choice.value);
        const filenamesByToken = new Map(
                choices.map(choice => [choice.value, choice.dataset.filename])
        );
        const description = tokens.length === 1
                ? '"' + filenamesByToken.get(tokens[0]) + '"'
                : tokens.length + " selected files";

        if (!window.confirm(
                "Permanently delete " + description + "?"
        )) {
            return;
        }

        fileActionInProgress = true;
        updateFileControls();
        fileActionStatus.textContent = "Deleting selected files...";

        try {
            const response = await fetch("/files", {
                method: "DELETE",
                headers: {"Content-Type": "application/json", "X-CSRF-Token": document.getElementById("csrf-token").content},
                body: JSON.stringify({tokens})
            });
            const result = await response.json();

            if (!response.ok) {
                fileActionStatus.textContent = result.message;
                return;
            }

            const deletedFilenames = result.deletedTokens
                    .map(token => filenamesByToken.get(token))
                    .filter(Boolean);

            result.deletedTokens.forEach(removeFileFromPage);

            storageStatus.textContent = result.storageLabel;
            fileActionStatus.textContent = result.message;
            updateFileControls();
            showFileActionNotification(
                    "Deleted",
                    deletedFilenames,
                    5000
            );
        } catch (error) {
            fileActionStatus.textContent =
                    "The files could not be deleted because the server "
                    + "could not be reached.";
        } finally {
            fileActionInProgress = false;
            updateFileControls();
            scheduleAvailableFilesSync();
        }
    }

    function removeFileFromPage(token) {
        getFileChoices()
                .find(choice => choice.value === token)
                ?.closest("li")
                .remove();
        invalidateShareResult(token);
    }

    function invalidateShareResult(token) {
        if (lastShareTokens.has(token)) {
            shareResult.hidden = true;
            lastShareUrl = "";
            lastSharePath = "";
            lastShareTokens.clear();
        }
    }
