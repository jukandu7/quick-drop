const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const template = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/index.html'), 'utf8');
const script = [...template.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)]
    .map(match => match[1])
    .find(source => source.includes('function applyLiveStatus('));

function sliceBetween(source, start, end) {
    const from = start ? source.indexOf(start) : 0;
    const to = source.indexOf(end, from);
    assert.ok(from >= 0 && to > from, `Expected owner script section ${start || 'initialization'} through ${end}`);
    return source.slice(from, to);
}

// Run the template's real initialization, status callbacks, and display/copy
// helpers; upload, file-list polling, and click listeners are outside this test.
const ownerSource = sliceBetween(script, null, '    if ("EventSource" in window)')
    + sliceBetween(script, '    async function createShareLink(', '    async function copyText(')
    + sliceBetween(script, '    async function copyText(', '    function scheduleAvailableFilesSync(');

function element() {
    return {
        dataset: {}, hidden: false, disabled: false, textContent: '',
        getAttribute(name) {
            return Object.hasOwn(this, name) ? this[name] : null;
        },
        removeAttribute(name) {
            delete this[name];
        },
        scrollIntoView() {},
        addEventListener() {}
    };
}

function status(baseUrl, receivePath = '/receive/old-session') {
    return {
        shareBaseUrl: baseUrl,
        serverTime: '2026-09-21T23:00:00Z',
        receiving: {
            active: Boolean(receivePath),
            receivePath,
            expiresAt: receivePath ? '2026-09-22T00:00:00Z' : null,
            expirationLabel: receivePath ? 'Expires in 1 hour' : null
        }
    };
}

function ownerPage({clipboardDenied = false, fallbackSucceeds = false, fileChoices = []} = {}) {
    const elements = new Map([...template.matchAll(/\bid="([^"]+)"/g)]
        .map(match => [match[1], element()]));
    elements.get('receive-tools').dataset = {
        active: 'true',
        senderUrl: 'http://192.168.1.10:8080/receive/old-session'
    };
    const clipboardWrites = [];
    const fallbackWrites = [];
    const notifications = [];
    const temporaryInputs = [];
    const fetchCalls = [];
    const timers = new Map();
    let timerId = 0;
    let callbacks;
    const helpers = vm.runInNewContext(ownerSource + '\n({showShareResult, copyText, copyOwnerLink, createShareLink});', {
        document: {
            getElementById: id => elements.get(id),
            createElement(tag) {
                assert.equal(tag, 'textarea');
                const input = {
                    style: {}, removed: false,
                    select() {},
                    remove() { this.removed = true; }
                };
                temporaryInputs.push(input);
                return input;
            },
            body: {appendChild() {}},
            execCommand(command) {
                assert.equal(command, 'copy');
                if (fallbackSucceeds) fallbackWrites.push(temporaryInputs.at(-1).value);
                return fallbackSucceeds;
            }
        },
        window: {location: {port: '8080'}},
        URL,
        navigator: {clipboard: {writeText: async value => {
            if (clipboardDenied) throw new Error('Clipboard access denied');
            clipboardWrites.push(value);
        }}},
        QuickDropConnection: class {
            constructor(options) {
                callbacks = options;
            }
            refresh() {}
        },
        setTimeout(callback, milliseconds) {
            timers.set(++timerId, {callback, milliseconds});
            return timerId;
        },
        clearTimeout(id) {
            timers.delete(id);
        },
        scheduleAvailableFilesSync() {},
        updateFileControls() {},
        getFileChoices: () => fileChoices,
        async fetch(url, options) {
            assert.equal(url, '/share/bundles');
            fetchCalls.push({url, options});
            return {
                ok: true,
                json: async () => ({sharePath: '/share/bundles/created-bundle', fileCount: fileChoices.length})
            };
        },
        showNotification: message => notifications.push(message)
    });
    return {
        node: id => elements.get(id),
        clipboardWrites,
        fallbackWrites,
        notifications,
        temporaryInputs,
        fetchCalls,
        timers,
        showShare: helpers.showShareResult,
        copyOwnerLink: helpers.copyOwnerLink,
        createShareLink: helpers.createShareLink,
        setConnection: callbacks.onConnection,
        publish: snapshot => callbacks.onStatus(snapshot, {startedAt: Date.now(), elapsedMs: 0})
    };
}

function qrUrl(node) {
    const source = node.getAttribute('src');
    return source ? new URL(source, 'http://localhost:8080').searchParams.get('text') : null;
}

test('a new network address updates the owner, shared file, sender links, and all QR codes together without copying', () => {
    const page = ownerPage();
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080'));
    page.showShare('http://192.168.1.10:8080/share/bundles/still-valid-token', 2);

    page.publish(status('http://10.0.0.24:8080'));

    assert.equal(page.node('owner-address').href, 'http://10.0.0.24:8080');
    assert.equal(page.node('owner-address').textContent, 'http://10.0.0.24:8080');
    assert.equal(qrUrl(page.node('owner-qr')), 'http://10.0.0.24:8080');
    assert.equal(page.node('share-url').href, 'http://10.0.0.24:8080/share/bundles/still-valid-token');
    assert.equal(page.node('share-url').textContent, page.node('share-url').href);
    assert.equal(qrUrl(page.node('share-qr')), page.node('share-url').href);
    assert.equal(page.node('receive-url').href, 'http://10.0.0.24:8080/receive/old-session');
    assert.equal(page.node('receive-url').textContent, page.node('receive-url').href);
    assert.equal(qrUrl(page.node('receive-qr')), page.node('receive-url').href);
    for (const id of ['owner-address', 'owner-qr', 'share-url', 'share-qr', 'receive-url', 'receive-qr']) {
        assert.equal(page.node(id).hidden, false, `${id} should be visible after the address refresh`);
    }
    assert.equal(page.node('copy-again').disabled, false);
    assert.equal(page.node('copy-receive-link').disabled, false);
    assert.equal(page.node('copy-owner-link').disabled, false);
    assert.deepEqual(page.clipboardWrites, []);
    assert.equal(page.notifications.length, 1);
});

test('missing local network and server disconnection hide device links and disable copying until recovery', () => {
    const page = ownerPage();
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080'));
    page.showShare('http://192.168.1.10:8080/share/file-token', 1);

    function assertLinksUnavailable() {
        for (const id of ['owner-address', 'owner-qr', 'share-url', 'share-qr', 'receive-url', 'receive-qr']) {
            assert.equal(page.node(id).hidden, true, `${id} must not show an unusable link`);
        }
        assert.equal(page.node('copy-again').disabled, true);
        assert.equal(page.node('copy-receive-link').disabled, true);
        assert.equal(page.node('copy-owner-link').disabled, true);
        assert.equal(page.node('start-receiving').disabled, true);
        assert.equal(page.node('network-status').hidden, false);
        assert.equal(page.node('share-network-status').hidden, false);
    }

    page.publish(status(null));
    assertLinksUnavailable();
    assert.match(page.node('network-status').textContent, /No local network address/);
    assert.equal(page.node('connection-status').dataset.state, 'connected');

    page.publish(status('http://10.0.0.24:8080'));
    assert.equal(page.node('copy-again').disabled, false);
    page.setConnection('reconnecting');
    assertLinksUnavailable();
    page.setConnection('unavailable');
    assertLinksUnavailable();
    page.setConnection('locked');
    assertLinksUnavailable();
    assert.equal(page.node('owner-unlock').hidden, false);

    page.setConnection('connected');
    page.publish(status('http://10.0.0.25:8080'));
    assert.equal(page.node('owner-address').hidden, false);
    assert.equal(page.node('share-url').href, 'http://10.0.0.25:8080/share/file-token');
    assert.equal(page.node('copy-again').disabled, false);
    assert.equal(page.node('copy-receive-link').disabled, false);
    assert.equal(page.node('copy-owner-link').disabled, false);
    assert.equal(page.node('owner-qr').hidden, false);
    assert.equal(qrUrl(page.node('owner-qr')), 'http://10.0.0.25:8080');
    assert.deepEqual(page.clipboardWrites, []);
});

test('receiving session changes remove an ended sender token and render only the replacement token', () => {
    const page = ownerPage();
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080'));
    assert.equal(page.node('receive-result').hidden, false);
    assert.match(qrUrl(page.node('receive-qr')), /old-session$/);
    assert.equal(page.timers.size, 1);

    page.publish(status('http://192.168.1.10:8080', null));
    assert.equal(page.node('receive-result').hidden, true);
    assert.equal(page.node('receive-start-controls').hidden, false);
    assert.equal(page.node('receive-url').getAttribute('href'), null);
    assert.equal(page.node('receive-url').textContent, '');
    assert.equal(page.node('receive-qr').getAttribute('src'), null);
    assert.equal(page.node('copy-receive-link').disabled, true);
    assert.equal(page.node('owner-qr').hidden, false);
    assert.equal(qrUrl(page.node('owner-qr')), 'http://192.168.1.10:8080');
    assert.equal(page.node('copy-owner-link').disabled, false);
    assert.match(page.node('receive-status').textContent, /ended/);
    assert.equal(page.timers.size, 0);

    page.publish(status('http://192.168.1.10:8080', '/receive/new-session'));
    assert.equal(page.node('receive-result').hidden, false);
    assert.equal(page.node('receive-start-controls').hidden, true);
    assert.equal(page.node('receive-url').href, 'http://192.168.1.10:8080/receive/new-session');
    assert.equal(page.node('receive-url').textContent, page.node('receive-url').href);
    assert.equal(qrUrl(page.node('receive-qr')), page.node('receive-url').href);
    assert.doesNotMatch(page.node('receive-url').href, /old-session/);
    assert.doesNotMatch(page.node('receive-qr').src, /old-session/);
    assert.equal(page.node('copy-receive-link').disabled, false);
    assert.equal(page.timers.size, 1);
    assert.deepEqual(page.clipboardWrites, []);
});

test('the primary device QR works with guest receiving off and remains available after guest expiry', () => {
    const page = ownerPage();
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080', null));
    assert.equal(page.node('owner-address').hidden, false);
    assert.equal(page.node('owner-qr').hidden, false);
    assert.equal(qrUrl(page.node('owner-qr')), 'http://192.168.1.10:8080');
    assert.equal(page.node('copy-owner-link').disabled, false);

    page.publish(status('http://192.168.1.10:8080'));
    assert.equal(page.timers.size, 1);
    [...page.timers.values()][0].callback();
    assert.equal(page.node('receive-result').hidden, true);
    assert.equal(page.node('receive-qr').getAttribute('src'), null);
    assert.match(page.node('receive-status').textContent, /expired/);
    assert.equal(page.node('owner-address').hidden, false);
    assert.equal(page.node('owner-qr').hidden, false);
    assert.equal(qrUrl(page.node('owner-qr')), 'http://192.168.1.10:8080');
    assert.equal(page.node('copy-owner-link').disabled, false);
});

test('copying a device link copies only the current dashboard address and confirms success', async () => {
    const page = ownerPage();
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080'));
    await page.copyOwnerLink();
    assert.deepEqual(page.clipboardWrites, ['http://192.168.1.10:8080']);
    assert.deepEqual(page.notifications, ['Device link copied.']);
    const copied = new URL(page.clipboardWrites[0]);
    assert.equal(copied.pathname, '/');
    assert.equal(copied.search, '');
    assert.equal(copied.hash, '');
    assert.equal(copied.username, '');
    assert.equal(copied.password, '');

    page.publish(status('http://10.0.0.24:8080', '/receive/new-session'));
    await page.copyOwnerLink();
    assert.equal(page.clipboardWrites.at(-1), 'http://10.0.0.24:8080');
    assert.equal(page.notifications.at(-1), 'Device link copied.');
});

test('device link copying does nothing before a usable address or while disconnected or locked', async () => {
    const page = ownerPage();
    await page.copyOwnerLink();
    page.setConnection('connected');
    page.publish(status(null));
    await page.copyOwnerLink();
    page.publish(status('http://192.168.1.10:8080'));
    for (const state of ['checking', 'reconnecting', 'unavailable', 'locked']) {
        page.setConnection(state);
        await page.copyOwnerLink();
    }
    assert.deepEqual(page.clipboardWrites, []);
    assert.deepEqual(page.fallbackWrites, []);
    assert.deepEqual(page.notifications, []);
});

test('device link copying supports a successful HTTP clipboard fallback and cleans up its input', async () => {
    const page = ownerPage({clipboardDenied: true, fallbackSucceeds: true});
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080', null));
    await page.copyOwnerLink();
    assert.deepEqual(page.clipboardWrites, []);
    assert.deepEqual(page.fallbackWrites, ['http://192.168.1.10:8080']);
    assert.deepEqual(page.notifications, ['Device link copied.']);
    assert.equal(page.temporaryInputs.length, 1);
    assert.equal(page.temporaryInputs[0].removed, true);
});

test('failed device link copying is handled without claiming success or leaving a temporary input', async () => {
    const page = ownerPage({clipboardDenied: true, fallbackSucceeds: false});
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080', null));
    await assert.doesNotReject(page.copyOwnerLink());
    assert.deepEqual(page.clipboardWrites, []);
    assert.deepEqual(page.fallbackWrites, []);
    assert.deepEqual(page.notifications, ['Device link could not be copied. You can scan the QR code instead.']);
    assert.equal(page.temporaryInputs.length, 1);
    assert.equal(page.temporaryInputs[0].removed, true);
});

for (const fileCount of [1, 2]) {
    test(`creating a ${fileCount}-file share still displays its link and QR code when clipboard copying is blocked`, async () => {
        const fileChoices = Array.from({length: fileCount}, (_, index) => ({
            value: `token-${index}`,
            dataset: {filename: `file-${index}.txt`, sharePath: `/share/token-${index}`}
        }));
        const page = ownerPage({clipboardDenied: true, fallbackSucceeds: false, fileChoices});
        page.setConnection('connected');
        page.publish(status('http://192.168.1.10:8080', null));
        page.node('share-result').hidden = true;
        await assert.doesNotReject(page.createShareLink(fileChoices));

        const expectedUrl = 'http://192.168.1.10:8080'
            + (fileCount === 1 ? '/share/token-0' : '/share/bundles/created-bundle');
        assert.equal(page.node('share-result').hidden, false);
        assert.equal(page.node('share-url').hidden, false);
        assert.equal(page.node('share-url').href, expectedUrl);
        assert.equal(page.node('share-url').textContent, expectedUrl);
        assert.equal(page.node('share-qr').hidden, false);
        assert.equal(qrUrl(page.node('share-qr')), expectedUrl);
        assert.equal(page.node('file-action-status').textContent,
            'Share link created. Copying was blocked; scan the QR code or copy the displayed link.');
        assert.equal(page.node('share-files-label').textContent,
            fileCount === 1 ? 'File: file-0.txt' : 'Link for 2 selected files.');
        assert.deepEqual(page.clipboardWrites, []);
        assert.deepEqual(page.fallbackWrites, []);
        assert.equal(page.temporaryInputs[0].removed, true);
        assert.equal(page.fetchCalls.length, fileCount === 1 ? 0 : 1);
        if (fileCount > 1) {
            assert.equal(page.fetchCalls[0].options.method, 'POST');
            assert.deepEqual(JSON.parse(page.fetchCalls[0].options.body), {tokens: ['token-0', 'token-1']});
        }
    });
}

test('creating a share preserves the successful copy message and visible share result', async () => {
    const fileChoices = [{value: 'file-token', dataset: {filename: 'notes.txt', sharePath: '/share/file-token'}}];
    const page = ownerPage({fileChoices});
    page.setConnection('connected');
    page.publish(status('http://192.168.1.10:8080', null));
    page.node('share-result').hidden = true;
    await page.createShareLink(fileChoices);
    assert.equal(page.node('share-result').hidden, false);
    assert.equal(qrUrl(page.node('share-qr')), 'http://192.168.1.10:8080/share/file-token');
    assert.deepEqual(page.clipboardWrites, ['http://192.168.1.10:8080/share/file-token']);
    assert.deepEqual(page.notifications, ['Share link created and copied!']);
    assert.equal(page.node('file-action-status').textContent, 'File link copied.');
});

test('the dashboard places full device access ahead of collapsed guest-only access', () => {
    const guest = /<details\b[^>]*\bid="guest-access"[^>]*>/i.exec(template);
    assert.ok(guest, 'Guest access should be an optional details section');
    assert.doesNotMatch(guest[0], /\sopen(?:\s|=|>)/i, 'Guest tools must start collapsed');
    const primary = template.slice(0, guest.index);
    assert.match(primary, /Connect another device/);
    assert.match(primary, /id="owner-qr"/);
    assert.match(primary, /id="copy-owner-link"/);
    assert.match(primary, /(?:four|4)[- ]digit (?:owner )?PIN/i);
    for (const capability of [/upload/i, /(?:view|files appear)/i, /download/i, /delet/i]) {
        assert.match(primary, capability);
    }
    const guestSection = template.slice(guest.index, template.indexOf('</details>', guest.index));
    assert.match(guestSection, /Let someone send files/);
    assert.match(guestSection, /(?:cannot|can't|without)[\s\S]*(?:see|seeing|view)[\s\S]*files/i);
    assert.match(template, /<meta\b[^>]*name="viewport"[^>]*content="width=device-width,\s*initial-scale=1(?:\.0)?"/i);
});
