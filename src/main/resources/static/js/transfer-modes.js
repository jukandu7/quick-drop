class QuickDropModes {
    constructor({sendButton, receiveButton, sendPanel, receivePanel, onReceive = () => {}}) {
        this.sendButton = sendButton;
        this.receiveButton = receiveButton;
        this.sendPanel = sendPanel;
        this.receivePanel = receivePanel;
        this.onReceive = onReceive;
        this.mode = null;
        this.started = false;
        this.sendClick = () => this.show("send");
        this.receiveClick = () => this.show("receive");
    }

    start() {
        if (this.started) return;
        this.started = true;
        this.sendButton.addEventListener("click", this.sendClick);
        this.receiveButton.addEventListener("click", this.receiveClick);
        this.show(this.mode || "send");
    }

    show(mode) {
        if (mode !== "send" && mode !== "receive") return;
        const changed = mode !== this.mode;
        this.mode = mode;
        this.sendPanel.hidden = mode !== "send";
        this.receivePanel.hidden = mode !== "receive";
        this.sendButton.setAttribute("aria-pressed", String(mode === "send"));
        this.receiveButton.setAttribute("aria-pressed", String(mode === "receive"));
        if (changed && mode === "receive") this.onReceive();
    }

    stop() {
        if (!this.started) return;
        this.started = false;
        this.sendButton.removeEventListener("click", this.sendClick);
        this.receiveButton.removeEventListener("click", this.receiveClick);
    }
}
