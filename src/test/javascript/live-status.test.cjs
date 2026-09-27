const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../main/resources');

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((accept, refuse) => {
        resolve = accept;
        reject = refuse;
    });
    return {promise, resolve, reject};
}

async function settle() {
    // Drain the fetch, JSON, catch, and finalization promise continuations.
    for (let i = 0; i < 20; i++) await Promise.resolve();
}

function eventSurface(extra = {}) {
    const listeners = new Map();
    return {
        ...extra,
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(listener);
        },
        removeEventListener(type, listener) {
            listeners.get(type)?.delete(listener);
        },
        dispatch(type) {
            for (const listener of listeners.get(type) || []) listener({type});
        },
        listenerCount() {
            return [...listeners.values()].reduce((count, set) => count + set.size, 0);
        }
    };
}

function fakeClock() {
    let now = 0;
    let nextId = 1;
    const timers = new Map();
    return {
        setTimeout(callback, delay = 0) {
            const id = nextId++;
            timers.set(id, {callback, due: now + delay});
            return id;
        },
        clearTimeout(id) {
            timers.delete(id);
        },
        async advance(milliseconds) {
            const end = now + milliseconds;
            await settle();
            while (true) {
                const next = [...timers.entries()]
                    .filter(([, timer]) => timer.due <= end)
                    .sort((a, b) => a[1].due - b[1].due)[0];
                if (!next) break;
                const [id, timer] = next;
                now = timer.due;
                timers.delete(id);
                timer.callback();
                await settle();
            }
            now = end;
            await settle();
        },
        count() {
            return timers.size;
        },
        now() {
            return now;
        }
    };
}

function snapshot(overrides = {}) {
    return {
        serverTime: '2026-09-21T23:00:00Z',
        network: {available: true, address: '192.168.1.10'},
        receiving: {enabled: true},
        ...overrides
    };
}

function response(body = snapshot(), status = 200) {
    return {ok: status >= 200 && status < 300, status, json: async () => body};
}

function harness(t, fetchOverride) {
    const clock = fakeClock();
    const document = eventSurface({visibilityState: 'visible', hidden: false});
    const window = eventSurface();
    const calls = [];
    const states = [];
    const statuses = [];
    const fetch = (url, options) => {
        const pending = deferred();
        calls.push({url, options, ...pending});
        return pending.promise;
    };
    const globals = {
        window, document, fetch: fetchOverride || fetch, AbortController,
        navigator: {onLine: true},
        performance: {now: clock.now},
        setTimeout: clock.setTimeout,
        clearTimeout: clock.clearTimeout,
        console
    };
    Object.assign(window, globals);
    const source = fs.readFileSync(path.join(resources, 'static/js/live-status.js'), 'utf8');
    const Connection = vm.runInNewContext(source + '\nQuickDropConnection;', globals);
    const connection = new Connection({
        url: '/api/status',
        onStatus: status => statuses.push(status),
        onConnection: state => states.push(state)
    });
    t.after(() => connection.stop());
    return {connection, clock, document, window, calls, states, statuses};
}

test('start checks immediately and polls five seconds after each request finishes', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    assert.deepEqual(h.states, ['checking']);
    assert.equal(h.calls.length, 1);
    assert.equal(h.calls[0].url, '/api/status');
    assert.equal(h.calls[0].options.cache, 'no-store');
    assert.ok(h.calls[0].options.signal instanceof AbortSignal);
    assert.ok(!h.calls[0].options.method || h.calls[0].options.method === 'GET');

    await h.clock.advance(1000);
    h.calls[0].resolve(response());
    await settle();
    assert.deepEqual(h.states, ['checking', 'connected']);
    assert.equal(h.statuses.length, 1);

    await h.clock.advance(4999);
    assert.equal(h.calls.length, 1);
    await h.clock.advance(1);
    assert.equal(h.calls.length, 2);
});

test('transient failures progress to unavailable and successful requests restore the connection', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    h.calls[0].resolve(response());
    await settle();

    let result = h.connection.refresh();
    await settle();
    h.calls[1].reject(new TypeError('Network unavailable'));
    assert.equal(await result, null);
    assert.equal(h.states.at(-1), 'reconnecting');

    result = h.connection.refresh();
    await settle();
    h.calls[2].resolve(response({}, 503));
    assert.equal(await result, null);
    assert.equal(h.states.at(-1), 'unavailable');

    result = h.connection.refresh();
    await settle();
    const recovered = snapshot({serverTime: '2026-09-21T23:00:15Z'});
    h.calls[3].resolve(response(recovered));
    assert.equal(await result, recovered);
    assert.deepEqual(h.states, ['checking', 'connected', 'reconnecting', 'unavailable', 'connected']);

    result = h.connection.refresh();
    await settle();
    h.calls[4].reject(new TypeError('Another temporary interruption'));
    await result;
    assert.equal(h.states.at(-1), 'reconnecting', 'a successful response resets the failure count');
});

test('401 means locked, remains locked on repeated 401s, and can recover', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    h.calls[0].resolve(response({}, 401));
    await settle();
    assert.deepEqual(h.states, ['checking', 'locked']);
    assert.equal(h.statuses.length, 0);

    let result = h.connection.refresh();
    await settle();
    h.calls[1].resolve(response({}, 401));
    assert.equal(await result, null);
    assert.deepEqual(h.states, ['checking', 'locked']);

    result = h.connection.refresh();
    await settle();
    h.calls[2].resolve(response());
    await result;
    assert.deepEqual(h.states, ['checking', 'locked', 'connected']);
});

test('successful HTTP responses require JSON with a valid server timestamp', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    h.calls[0].resolve(response({serverTime: 'not a date'}));
    await settle();
    assert.equal(h.states.at(-1), 'reconnecting');
    assert.equal(h.statuses.length, 0);

    let result = h.connection.refresh();
    await settle();
    h.calls[1].resolve(response({}));
    assert.equal(await result, null);
    assert.equal(h.states.at(-1), 'unavailable');

    result = h.connection.refresh();
    await settle();
    h.calls[2].resolve({ok: true, status: 200, json: async () => { throw new SyntaxError('Invalid JSON'); }});
    assert.equal(await result, null);
    assert.equal(h.statuses.length, 0);

    result = h.connection.refresh();
    await settle();
    h.calls[3].resolve(response());
    await result;
    assert.equal(h.states.at(-1), 'connected');
    assert.equal(h.statuses.length, 1);
});

test('a hanging fetch aborts after four seconds and the next poll can recover', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    const first = h.calls[0];
    first.options.signal.addEventListener('abort', () => {
        const error = new Error('Request aborted');
        error.name = 'AbortError';
        first.reject(error);
    });
    const pending = h.connection.refresh();

    await h.clock.advance(3999);
    assert.equal(first.options.signal.aborted, false);
    assert.deepEqual(h.states, ['checking']);
    await h.clock.advance(1);
    assert.equal(first.options.signal.aborted, true);
    assert.equal(await pending, null);
    assert.equal(h.states.at(-1), 'reconnecting');

    await h.clock.advance(4999);
    assert.equal(h.calls.length, 1);
    await h.clock.advance(1);
    assert.equal(h.calls.length, 2);
    h.calls[1].resolve(response());
    await settle();
    assert.equal(h.states.at(-1), 'connected');
    assert.equal(h.statuses.length, 1);
});

test('simultaneous refreshes and browser events share one in-flight request', async t => {
    const h = harness(t);
    h.connection.start();
    const first = h.connection.refresh();
    const second = h.connection.refresh();
    h.window.dispatch('focus');
    h.window.dispatch('online');
    h.document.dispatch('visibilitychange');
    await settle();
    assert.equal(first, second);
    assert.equal(h.calls.length, 1);

    const current = snapshot();
    h.calls[0].resolve(response(current));
    assert.equal(await first, current);
    assert.equal(await second, current);
    assert.equal(h.statuses.length, 1);
    await h.clock.advance(5000);
    assert.equal(h.calls.length, 2);
});

test('each fresh snapshot reaches consumers even while connection state stays connected', async t => {
    const h = harness(t);
    const initial = snapshot();
    h.connection.start();
    await settle();
    h.calls[0].resolve(response(initial));
    await settle();

    const changed = snapshot({
        serverTime: '2026-09-21T23:00:05Z',
        network: {available: false, address: null},
        receiving: {enabled: false}
    });
    const pending = h.connection.refresh();
    await settle();
    h.calls[1].resolve(response(changed));
    assert.equal(await pending, changed);
    assert.deepEqual(h.statuses, [initial, changed]);
    assert.deepEqual(h.states, ['checking', 'connected']);
});

test('focus, online, offline, and visible transitions immediately request fresh status', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    h.calls[0].resolve(response());
    await settle();

    for (const event of ['focus', 'online', 'offline']) {
        const before = h.calls.length;
        h.window.dispatch(event);
        await settle();
        assert.equal(h.calls.length, before + 1, `${event} should trigger a check`);
        h.calls.at(-1).resolve(response());
        await settle();
    }

    h.document.visibilityState = 'hidden';
    h.document.hidden = true;
    h.document.dispatch('visibilitychange');
    await settle();
    assert.equal(h.calls.length, 4);
    h.document.visibilityState = 'visible';
    h.document.hidden = false;
    h.document.dispatch('visibilitychange');
    await settle();
    assert.equal(h.calls.length, 5);
});

test('stop aborts a pending request, removes listeners, and discards late replies', async t => {
    const h = harness(t);
    h.connection.start();
    await settle();
    const pending = h.connection.refresh();
    assert.ok(h.window.listenerCount() > 0);
    assert.ok(h.document.listenerCount() > 0);
    h.connection.stop();
    assert.equal(h.calls[0].options.signal.aborted, true);
    assert.equal(h.clock.count(), 0);
    assert.equal(h.window.listenerCount(), 0);
    assert.equal(h.document.listenerCount(), 0);

    h.calls[0].resolve(response());
    assert.equal(await pending, null);
    h.window.dispatch('focus');
    h.window.dispatch('online');
    h.document.dispatch('visibilitychange');
    await h.clock.advance(20000);
    assert.equal(h.calls.length, 1);
    assert.equal(h.statuses.length, 0);
    assert.deepEqual(h.states, ['checking']);
    assert.equal(h.clock.count(), 0);
});

test('stop also discards a response whose JSON body is still pending', async t => {
    const h = harness(t);
    const body = deferred();
    h.connection.start();
    await settle();
    const pending = h.connection.refresh();
    h.calls[0].resolve({ok: true, status: 200, json: () => body.promise});
    await settle();
    h.connection.stop();
    body.resolve(snapshot());
    assert.equal(await pending, null);
    await h.clock.advance(10000);
    assert.equal(h.statuses.length, 0);
    assert.deepEqual(h.states, ['checking']);
    assert.equal(h.clock.count(), 0);
});

test('a synchronous fetch error releases the pending request so polling recovers', async t => {
    let attempts = 0;
    const h = harness(t, () => {
        attempts++;
        if (attempts === 1) throw new TypeError('Fetch could not start');
        return Promise.resolve(response());
    });
    h.connection.start();
    await settle();
    assert.deepEqual(h.states, ['checking', 'reconnecting']);
    await h.clock.advance(5000);
    assert.equal(attempts, 2);
    assert.deepEqual(h.states, ['checking', 'reconnecting', 'connected']);
    assert.equal(h.statuses.length, 1);
});

test('stopping immediately prevents deferred work from sending a new request', async t => {
    const h = harness(t);
    const pending = h.connection.start();
    h.connection.stop();
    await settle();
    assert.equal(h.calls.length, 0);
    assert.equal(await pending, null);
    assert.deepEqual(h.states, ['checking']);
    assert.equal(h.clock.count(), 0);
});

test('page inline scripts remain valid JavaScript', () => {
    for (const name of ['index.html', 'receive.html', 'owner-login.html', 'owner-setup.html']) {
        const html = fs.readFileSync(path.join(resources, 'templates', name), 'utf8');
        for (const [index, match] of [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].entries()) {
            if (!match[1].trim()) continue;
            assert.doesNotThrow(() => new vm.Script(match[1], {filename: `${name}:script-${index + 1}`}));
        }
    }
});
