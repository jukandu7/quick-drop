const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const template = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/index.html'), 'utf8');
const source = template.slice(template.indexOf('    async function revokeDownloadLinks('), template.indexOf('    async function deleteFiles('));

function page({confirm = true, ok = true, failure = false} = {}) {
    const calls = [], invalidated = [];
    let refreshes = 0;
    const status = {textContent: ''};
    const context = vm.createContext({
        fileActionInProgress: false,
        window: {confirm: () => confirm},
        document: {getElementById: () => ({content: 'session-token'})},
        updateFileControls() {},
        fileActionStatus: status,
        invalidateShareResult: token => invalidated.push(token),
        scheduleAvailableFilesSync: () => refreshes++,
        fetch: async (url, options) => {
            calls.push({url, options});
            if (failure) throw new Error('offline');
            return {ok, json: async () => ({message: ok ? 'Revoked' : 'Denied', revokedTokens: ['old-token']})};
        }
    });
    vm.runInContext(source, context);
    return {context, calls, invalidated, status, refreshes: () => refreshes};
}

test('revocation sends selected tokens and CSRF header, clears stale shares, and refreshes', async () => {
    const p = page();
    await p.context.revokeDownloadLinks([{value: 'old-token'}]);
    assert.equal(p.calls[0].url, '/share-links');
    assert.equal(p.calls[0].options.method, 'DELETE');
    assert.equal(p.calls[0].options.headers['X-CSRF-Token'], 'session-token');
    assert.deepEqual(JSON.parse(p.calls[0].options.body), {tokens: ['old-token']});
    assert.deepEqual(p.invalidated, ['old-token']);
    assert.equal(p.refreshes(), 1);
    assert.equal(p.context.fileActionInProgress, false);
});

test('cancelling revocation does not send a request', async () => {
    const p = page({confirm: false});
    await p.context.revokeDownloadLinks([{value: 'old-token'}]);
    assert.equal(p.calls.length, 0);
});

test('rejected and failed requests do not claim success and release the controls', async () => {
    for (const options of [{ok: false}, {failure: true}]) {
        const p = page(options);
        await p.context.revokeDownloadLinks([{value: 'old-token'}]);
        assert.deepEqual(p.invalidated, []);
        assert.equal(p.context.fileActionInProgress, false);
        assert.notEqual(p.status.textContent, 'Revoked');
    }
});
