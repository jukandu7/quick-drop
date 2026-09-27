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
