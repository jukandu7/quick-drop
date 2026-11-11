const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources');

for (const page of ['index', 'receive']) {
    test(`${page} external scripts load in template order and initialize`, () => {
        const html = fs.readFileSync(path.join(root, 'templates', page + '.html'), 'utf8');
        const elements = new Map();
        for (const [, id] of html.matchAll(/\bid="([^"]+)"/g)) {
            elements.set(id, {
                dataset: {label: '3 GB'}, content: '3221225472', action: '/receive/token/upload',
                hidden: false, disabled: false, value: '', textContent: '',
                addEventListener() {}, removeEventListener() {},
                querySelectorAll() { return []; },
                setAttribute(name, value) { this[name] = value; },
                getAttribute(name) { return this[name] ?? null; },
                removeAttribute(name) { delete this[name]; },
                classList: {add() {}, remove() {}, toggle() {}}
            });
        }
        const lifecycle = [];
        let started = 0;
        const context = vm.createContext({
            document: {getElementById: id => elements.get(id), body: {dataset: {}}},
            window: {addEventListener: (event, action) => lifecycle.push([event, action]), setInterval() {}},
            setTimeout() {}, clearTimeout() {}, URL, console,
            QuickDropConnection: class { start() { started++; } stop() {} refresh() {} },
        });
        for (const [, attrs, inline] of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)) {
            assert.equal(inline.trim(), '', 'page behavior belongs in external files');
            const src = /src="([^"]+)"/.exec(attrs)?.[1];
            assert.ok(src);
            const code = fs.readFileSync(path.join(root, 'static', src), 'utf8');
            assert.doesNotThrow(() => new vm.Script(code, {filename: src}));
            // Connection and mode behavior have dedicated tests; isolate page startup from timers/network.
            if (src === '/js/live-status.js') continue;
            vm.runInContext(code, context, {filename: src});
        }
        assert.equal(started, 1);
        assert.ok(lifecycle.some(([event]) => event === 'pagehide'));
    });
}
