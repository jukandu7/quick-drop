// Keep the original batch intact so retries update results, not the total.
class UploadBatch {
    constructor(files, options = {}) {
        this.options = {...options};
        this.entries = Array.from(files, file => ({
            file,
            filename: file.name,
            status: "pending",
            message: "",
            retryable: false
        }));
    }

    finish(entry, outcome) {
        entry.status = outcome.success ? "uploaded"
                : outcome.skipped ? "skipped" : "failed";
        entry.message = outcome.message || (outcome.skipped
                ? "Skipped" : outcome.success ? "Uploaded" : "Upload failed.");
        entry.retryable = entry.status === "failed"
                && outcome.retryable === true;

        // Only failed, retryable files need to stay available in this tab.
        if (!entry.retryable) {
            entry.file = null;
        }
    }

    retryableEntries() {
        return this.entries.filter(entry => entry.status === "failed"
                && entry.retryable && entry.file);
    }

    issues() {
        return this.entries.filter(entry => entry.status === "failed"
                || entry.status === "skipped");
    }

    hasFailures() {
        return this.entries.some(entry => entry.status === "failed");
    }

    summary() {
        const count = status => this.entries
                .filter(entry => entry.status === status).length;
        const skipped = count("skipped");
        return count("uploaded") + " uploaded, " + count("failed")
                + " failed" + (skipped ? ", " + skipped + " skipped" : "") + ".";
    }
}

// Shared presentation for owner and guest uploads. Only a server response confirms success.
class UploadFeedback {
    static prepare(row, file, sizeLabel) {
        row.dataset.state = 'sending';
        row.querySelector('strong').textContent = file.name;
        const progress = row.querySelector('progress');
        progress.hidden = false;
        progress.value = 0;
        progress.setAttribute('aria-label', 'Sending ' + file.name);
        const status = row.querySelector('small');
        status.hidden = false;
        status.textContent = sizeLabel + ' · Ready to send';
    }

    static progress(row, event) {
        const progress = row.querySelector('progress');
        const status = row.querySelector('small');
        if (!event.lengthComputable || event.total <= 0) {
            progress.removeAttribute('value');
            status.textContent = 'Sending file…';
            return;
        }
        const percent = Math.min(100, Math.floor(event.loaded / event.total * 100));
        if (percent >= 100) {
            row.dataset.state = 'saving';
            progress.removeAttribute('value');
            progress.setAttribute('aria-label', 'Waiting for server confirmation');
            status.textContent = 'Sent to Quick Drop · waiting for confirmation. Keep this tab open.';
        } else {
            row.dataset.state = 'sending';
            progress.value = percent;
            status.textContent = 'Sending · ' + percent + '%';
        }
    }

    static finish(row, batch, queue) {
        const issues = batch.issues();
        const uploaded = batch.entries.filter(entry => entry.status === 'uploaded').length;
        const retryCount = batch.retryableEntries().length;
        row.dataset.state = batch.hasFailures() ? 'attention' : issues.length ? 'skipped' : 'complete';
        row.querySelector('strong').textContent = batch.hasFailures() ? 'Some files need attention'
                : issues.length ? 'Transfer finished with skipped files' : 'Transfer complete';
        row.querySelector('progress').hidden = true;
        const status = row.querySelector('small');
        status.hidden = false;
        status.textContent = uploaded + (uploaded === 1 ? ' file confirmed.' : ' files confirmed.')
                + (retryCount ? ' Retry is available for ' + retryCount + '. Check whether interrupted files arrived first.'
                    : batch.hasFailures() ? 'Review the results below before choosing files again.' : 'You can safely choose another batch.');
        const details = document.createElement('details');
        details.className = 'transfer-results';
        details.open = issues.length > 0;
        const summary = document.createElement('summary');
        summary.textContent = 'File results (' + batch.entries.length + ')';
        const list = document.createElement('ul');
        batch.entries.forEach(entry => {
            const item = document.createElement('li');
            item.dataset.state = entry.status;
            const name = document.createElement('strong');
            name.textContent = entry.filename;
            const message = document.createElement('span');
            message.textContent = entry.status === 'uploaded' ? 'Uploaded · confirmed by Quick Drop'
                    : (entry.status === 'skipped' ? 'Skipped · ' : 'Not confirmed · ') + entry.message;
            item.appendChild(name);
            item.appendChild(message);
            list.appendChild(item);
        });
        details.appendChild(summary);
        details.appendChild(list);
        queue.appendChild(details);
    }
}
