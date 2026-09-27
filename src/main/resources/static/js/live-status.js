class QuickDropConnection {
    constructor({url, onStatus, onConnection}) {
        this.url = url;
        this.onStatus = onStatus;
        this.onConnection = onConnection;
        this.state = "checking";
        this.running = false;
        this.generation = 0;
        this.failures = 0;
        this.pending = null;
        this.wake = () => this.refresh();
        this.onVisible = () => {
            if (document.visibilityState === "visible") this.refresh();
        };
    }

    start() {
        if (this.running) return this.pending;
        this.running = true;
        this.generation++;
        this.failures = 0;
        this.state = "checking";
        this.onConnection(this.state);
        ["online", "offline", "focus"].forEach(event =>
                window.addEventListener(event, this.wake));
        document.addEventListener("visibilitychange", this.onVisible);
        return this.refresh();
    }

    refresh() {
        if (!this.running) return Promise.resolve(null);
        if (this.pending) return this.pending;
        clearTimeout(this.pollTimer);

        const generation = this.generation;
        const startedAt = Date.now();
        const startTick = performance.now();
        const controller = new AbortController();
        this.controller = controller;
        const timeout = setTimeout(() => controller.abort(), 4000);
        this.requestTimer = timeout;
        const current = () => this.running && generation === this.generation;

        this.pending = Promise.resolve().then(async () => {
            try {
                if (!current()) return null;
                const response = await fetch(this.url, {
                    cache: "no-store",
                    signal: controller.signal
                });
                if (!current()) return null;
                if (response.status === 401) {
                    this.failures = 0;
                    this.setState("locked");
                    return null;
                }
                if (!response.ok) throw new Error("Status unavailable");
                const status = await response.json();
                if (!status || !Number.isFinite(Date.parse(status.serverTime))) {
                    throw new Error("Invalid status response");
                }
                if (!current()) return null;
                this.failures = 0;
                this.setState("connected");
                this.onStatus(status, {startedAt, elapsedMs: performance.now() - startTick});
                return status;
            } catch (error) {
                if (current()) {
                    this.failures++;
                    this.setState(this.failures === 1 ? "reconnecting" : "unavailable");
                }
                return null;
            } finally {
                clearTimeout(timeout);
                if (generation === this.generation) {
                    this.pending = null;
                    this.controller = null;
                    if (this.running) {
                        this.pollTimer = setTimeout(() => this.refresh(), 5000);
                    }
                }
            }
        });
        return this.pending;
    }

    setState(state) {
        if (state !== this.state) {
            this.state = state;
            this.onConnection(state);
        }
    }

    stop() {
        this.running = false;
        this.generation++;
        clearTimeout(this.pollTimer);
        clearTimeout(this.requestTimer);
        this.controller?.abort();
        this.controller = null;
        this.pending = null;
        ["online", "offline", "focus"].forEach(event =>
                window.removeEventListener(event, this.wake));
        document.removeEventListener("visibilitychange", this.onVisible);
    }
}
