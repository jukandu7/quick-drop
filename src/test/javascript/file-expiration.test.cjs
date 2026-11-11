const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = vm.createContext({});
vm.runInContext(fs.readFileSync('src/main/resources/static/js/dashboard-files.js', 'utf8'), context);
const now = Date.parse('2026-11-11T12:00:00Z');
const label = seconds => context.relativeExpiration(new Date(now + seconds * 1000).toISOString(), now);
test('expiration labels cover past, imminent, minute, hour, and day boundaries', () => {
    assert.equal(label(-1), 'Expired — awaiting removal');
    assert.equal(label(0), 'Expired — awaiting removal');
    assert.equal(label(30), 'Expires in less than a minute');
    assert.equal(label(60), 'Expires in about 1 minute');
    assert.equal(label(3599), 'Expires in about 60 minutes');
    assert.equal(label(3600), 'Expires in about 1 hour');
    assert.equal(label(7200), 'Expires in about 2 hours');
    assert.equal(label(86400), 'Expires in about 1 day');
});
test('permanent or unavailable expiration has no misleading countdown', () => {
    for (const value of [null, undefined, '', 'invalid']) assert.equal(context.relativeExpiration(value, now), '');
});
test('relative expiry uses the instant rather than the displayed timezone', () => {
    assert.equal(context.relativeExpiration('2026-11-11T09:00:00-04:00', now), 'Expires in about 1 hour');
});
