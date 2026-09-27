const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resourceRoot = path.resolve(__dirname, '../../main/resources');
const source = fs.readFileSync(path.join(resourceRoot, 'static/js/transfer-modes.js'), 'utf8');
const template = fs.readFileSync(path.join(resourceRoot, 'templates/index.html'), 'utf8');

function button() {
    const listeners = new Set();
    const attributes = new Map();
    return {
        addEventListener(event, listener) {
            assert.equal(event, 'click');
            listeners.add(listener);
        },
        removeEventListener(event, listener) {
            assert.equal(event, 'click');
            listeners.delete(listener);
        },
        setAttribute(name, value) { attributes.set(name, String(value)); },
        getAttribute(name) { return attributes.get(name) ?? null; },
        click() { for (const listener of [...listeners]) listener({type: 'click'}); },
        listenerCount() { return listeners.size; }
    };
}

function panel(children) {
    return {
        hidden: false,
        children,
        set innerHTML(value) { assert.fail('Switching modes must not replace panel contents'); },
        replaceChildren() { assert.fail('Switching modes must not replace panel contents'); }
    };
}

function modesPage() {
    const file = {name: 'video.mp4', size: 1024};
    const uploadInput = {files: [file], value: 'video.mp4'};
    const expiration = {value: 'one-day'};
    const fileChoice = {checked: true, value: 'file-token'};
    const uploadProgress = {value: 42};
    const sendButton = button();
    const receiveButton = button();
    const sendPanel = panel([uploadInput, expiration, uploadProgress]);
    const receivePanel = panel([fileChoice]);
    let receives = 0;
    // No browser location, network, session, or authentication facilities are
    // provided: switching is solely a view change inside the unlocked page.
    const Modes = vm.runInNewContext(source + '\nQuickDropModes;');
    const modes = new Modes({
        sendButton, receiveButton, sendPanel, receivePanel,
        onReceive() { receives++; }
    });
    return {
        modes, sendButton, receiveButton, sendPanel, receivePanel,
        uploadInput, expiration, fileChoice, uploadProgress, file,
        receives: () => receives
    };
}

function assertMode(page, expected) {
    assert.equal(page.sendPanel.hidden, expected !== 'send');
    assert.equal(page.receivePanel.hidden, expected !== 'receive');
    assert.equal(page.sendButton.getAttribute('aria-pressed'), String(expected === 'send'));
    assert.equal(page.receiveButton.getAttribute('aria-pressed'), String(expected === 'receive'));
}

test('starts in Send mode with exactly one panel visible and matching accessible button state', () => {
    const page = modesPage();
    page.modes.start();
    assertMode(page, 'send');
    assert.equal(page.receives(), 0);
    assert.equal(page.sendButton.listenerCount(), 1);
    assert.equal(page.receiveButton.listenerCount(), 1);
});

test('each transition to Receive refreshes available files once, with no repeat for the active mode', () => {
    const page = modesPage();
    page.modes.start();
    page.receiveButton.click();
    assertMode(page, 'receive');
    assert.equal(page.receives(), 1);
    page.receiveButton.click();
    page.modes.show('receive');
    assert.equal(page.receives(), 1);
    page.sendButton.click();
    assertMode(page, 'send');
    assert.equal(page.receives(), 1);
    page.receiveButton.click();
    assertMode(page, 'receive');
    assert.equal(page.receives(), 2);
});

test('switching preserves input files, expiration, upload progress, and checked download selection', () => {
    const page = modesPage();
    const originalSendChildren = page.sendPanel.children;
    const originalReceiveChildren = page.receivePanel.children;
    page.modes.start();
    for (let index = 0; index < 3; index++) {
        page.receiveButton.click();
        page.sendButton.click();
    }
    assert.strictEqual(page.sendPanel.children, originalSendChildren);
    assert.strictEqual(page.receivePanel.children, originalReceiveChildren);
    assert.strictEqual(page.sendPanel.children[0], page.uploadInput);
    assert.strictEqual(page.uploadInput.files[0], page.file);
    assert.equal(page.uploadInput.value, 'video.mp4');
    assert.equal(page.expiration.value, 'one-day');
    assert.equal(page.uploadProgress.value, 42);
    assert.strictEqual(page.receivePanel.children[0], page.fileChoice);
    assert.equal(page.fileChoice.checked, true);
});

test('unknown modes leave the currently selected mode and receive callback unchanged', () => {
    const page = modesPage();
    page.modes.start();
    page.receiveButton.click();
    for (const invalid of ['', 'Send', 'upload', 'guest', null, undefined, {}, 0]) {
        page.modes.show(invalid);
        assertMode(page, 'receive');
        assert.equal(page.receives(), 1);
    }
});

test('start is idempotent and does not reset an already selected Receive mode', () => {
    const page = modesPage();
    page.modes.start();
    page.receiveButton.click();
    page.modes.start();
    page.modes.start();
    assertMode(page, 'receive');
    assert.equal(page.receives(), 1);
    assert.equal(page.sendButton.listenerCount(), 1);
    assert.equal(page.receiveButton.listenerCount(), 1);
    page.sendButton.click();
    page.receiveButton.click();
    assert.equal(page.receives(), 2);
});

test('stop removes only mode listeners and restart preserves the selected view', () => {
    const page = modesPage();
    let otherClicks = 0;
    const unrelatedListener = () => otherClicks++;
    page.receiveButton.addEventListener('click', unrelatedListener);
    page.modes.start();
    page.receiveButton.click();
    assert.equal(page.receives(), 1);
    page.modes.stop();
    page.modes.stop();
    assert.equal(page.sendButton.listenerCount(), 0);
    assert.equal(page.receiveButton.listenerCount(), 1);
    page.sendButton.click();
    page.receiveButton.click();
    assertMode(page, 'receive');
    assert.equal(page.receives(), 1);
    assert.equal(otherClicks, 2);
    page.modes.start();
    assertMode(page, 'receive');
    assert.equal(page.receiveButton.listenerCount(), 2);
    page.sendButton.click();
    page.receiveButton.click();
    assertMode(page, 'receive');
    assert.equal(page.receives(), 2);
    assert.equal(otherClicks, 3);
});

// Index just the real HTML before scripts. Ancestor identities allow checking
// panel containment without relying on whitespace or a DOM library dependency.
function markupElements() {
    const html = template.slice(0, template.indexOf('<script'));
    const elements = new Map();
    const stack = [];
    const voidTags = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);
    for (const match of html.matchAll(/<!--[\s\S]*?-->|<\/?[a-z][^>]*>/gi)) {
        const opening = /^<([a-z][\w:-]*)\b/i.exec(match[0]);
        const closing = /^<\/([a-z][\w:-]*)\b/i.exec(match[0]);
        if (closing) {
            const index = stack.findLastIndex(node => node.tag === closing[1].toLowerCase());
            if (index >= 0) {
                stack[index].end = match.index + match[0].length;
                stack.splice(index);
            }
        } else if (opening) {
            const id = /\bid="([^"]+)"/.exec(match[0])?.[1];
            const node = {tag: opening[1].toLowerCase(), id, start: match.index, opening: match[0], ancestors: [...stack]};
            if (id) {
                assert.equal(elements.has(id), false, `Duplicate element id: ${id}`);
                elements.set(id, node);
            }
            if (!voidTags.has(node.tag) && !/\/>$/.test(match[0])) stack.push(node);
        }
    }
    return elements;
}

test('the dashboard has one accessible mode switch and keeps each operation in its correct panel', () => {
    const elements = markupElements();
    const modeSwitch = elements.get('mode-switch');
    const sendPanel = elements.get('send-panel');
    const receivePanel = elements.get('receive-panel');
    assert.ok(modeSwitch && sendPanel && receivePanel);
    assert.ok(modeSwitch.start < sendPanel.start && modeSwitch.start < receivePanel.start);
    for (const [mode, panel] of [['send', sendPanel], ['receive', receivePanel]]) {
        const control = elements.get(`${mode}-mode-button`);
        assert.equal(control.tag, 'button');
        assert.match(control.opening, /\btype="button"/);
        assert.match(control.opening, new RegExp(`\\baria-controls="${mode}-panel"`));
        assert.match(control.opening, /\baria-pressed="(?:true|false)"/);
        assert.ok(control.ancestors.includes(modeSwitch));
        assert.equal(panel.ancestors.includes(modeSwitch), false);
    }
    for (const id of ['upload-form', 'file', 'expiration']) {
        assert.ok(elements.get(id)?.ancestors.includes(sendPanel), `${id} belongs in Send mode`);
    }
    const uploadActivity = elements.get('upload-activity');
    assert.ok(uploadActivity);
    assert.equal(uploadActivity.ancestors.includes(sendPanel), false);
    assert.equal(uploadActivity.ancestors.includes(receivePanel), false);
    for (const id of ['upload-queue', 'upload-status', 'retry-failed', 'retry-note']) {
        assert.ok(elements.get(id)?.ancestors.includes(uploadActivity), `${id} must stay available in either mode`);
    }
    for (const id of ['files-list', 'file-actions', 'download-selected', 'share-selected', 'delete-selected', 'share-result']) {
        assert.ok(elements.get(id)?.ancestors.includes(receivePanel), `${id} belongs in Receive mode`);
    }
    assert.match(receivePanel.opening, /\shidden(?:\s|>|=)/);
    assert.doesNotMatch(sendPanel.opening, /\shidden(?:\s|>|=)/);
    const controls = template.slice(modeSwitch.start, modeSwitch.end);
    assert.doesNotMatch(controls, /<a\b|<form\b|\bhref=|\/owner\/|\/receive\//i);
    assert.match(template, /<script\b[^>]*src="\/js\/transfer-modes\.js"/);
    assert.match(template, /new QuickDropModes\(/);
});

test('connection is available outside both modes with a typeable address before the optional QR', () => {
    const elements = markupElements();
    const connect = elements.get('connect-device');
    const address = elements.get('owner-address');
    const copy = elements.get('copy-owner-link');
    const qr = elements.get('owner-qr');
    assert.ok(connect && address && copy && qr);
    assert.ok(address.start < qr.start && copy.start < qr.start, 'Computers should see the address and copy button before the QR');
    for (const id of ['connect-device', 'guest-access']) {
        const node = elements.get(id);
        assert.ok(node);
        assert.equal(node.ancestors.some(ancestor => ['send-panel', 'receive-panel'].includes(ancestor.id)), false);
    }
    const connectionText = template.slice(connect.start, connect.end);
    assert.match(connectionText, /(?:computer|laptop|PC)/i);
    assert.match(connectionText, /tablet/i);
    assert.match(connectionText, /phone/i);
    assert.match(connectionText, /same (?:local )?(?:Wi-Fi|network)/i);
    assert.match(connectionText, /(?:type|enter|paste)[\s\S]*(?:address|browser)/i);
    assert.match(connectionText, /(?:four|4)[- ]digit (?:owner )?PIN/i);
});
