const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const resourceRoot = path.resolve(__dirname, '../../main/resources');
const template = fs.readFileSync(path.join(resourceRoot, 'templates/index.html'), 'utf8');
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


test('uploads and stored files are visible together in a single dashboard', () => {
    const elements = markupElements();
    const upload = elements.get('add-files-panel');
    const files = elements.get('your-files-panel');
    const activity = elements.get('upload-activity');
    assert.ok(upload.start < activity.start && activity.start < files.start);
    for (const section of [upload, activity, files]) {
        assert.doesNotMatch(section.opening, /\shidden(?:\s|>|=)/);
        assert.ok(section.ancestors.includes(elements.get('main-content')));
    }
    for (const id of ['upload-form', 'file', 'expiration']) assert.ok(elements.get(id).ancestors.includes(upload));
    for (const id of ['files-list', 'file-actions', 'share-result', 'file-management']) assert.ok(elements.get(id).ancestors.includes(files));
    assert.match(template, />Add files<\/h2>/);
    assert.match(template, />Your files<\/h2>/);
    assert.doesNotMatch(template, /mode-switch|transfer-modes\.js|open Receive files/);
});
test('connection is available beside file transfers with a typeable address before the optional QR', () => {
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
        assert.equal(node.ancestors.some(ancestor => ['add-files-panel', 'your-files-panel'].includes(ancestor.id)), false);
    }
    const connectionText = template.slice(connect.start, connect.end);
    assert.match(connectionText, /(?:computer|laptop|PC)/i);
    assert.match(connectionText, /tablet/i);
    assert.match(connectionText, /phone/i);
    assert.match(connectionText, /same (?:local )?(?:Wi-Fi|network)/i);
    assert.match(connectionText, /(?:type|enter|paste)[\s\S]*(?:address|browser)/i);
    assert.match(connectionText, /(?:four|4)[- ]digit (?:owner )?PIN/i);
});


test('connection details start collapsed behind an accessible toggle', () => {
    const elements = markupElements();
    const toggle = elements.get('connect-device-toggle');
    const panel = elements.get('connect-device');
    assert.equal(toggle.tag, 'button');
    assert.match(toggle.opening, /aria-expanded="false"/);
    assert.match(toggle.opening, /aria-controls="connect-device"/);
    assert.match(panel.opening, /\shidden(?:\s|>|=)/);
    assert.ok(elements.get('owner-address').ancestors.includes(panel));
    assert.ok(elements.get('copy-owner-link').ancestors.includes(panel));
    assert.ok(elements.get('device-qr-options').ancestors.includes(panel));
    const vm = require('node:vm');
    let click;
    const button = {expanded: 'false', getAttribute() {return this.expanded;}, setAttribute(name, value) {this.expanded = value;}, addEventListener(event, handler) {click = handler;}};
    const section = {hidden: true};
    const js = fs.readFileSync(path.join(resourceRoot, 'static/js/dashboard.js'), 'utf8');
    vm.runInNewContext(js.slice(js.indexOf('    const connectDeviceToggle'), js.indexOf('    browseFilesButton.addEventListener')), {
        document: {getElementById: id => id === 'connect-device-toggle' ? button : section}
    });
    click();
    assert.equal(section.hidden, false);
    assert.equal(button.expanded, 'true');
    click();
    assert.equal(section.hidden, true);
    assert.equal(button.expanded, 'false');
});
