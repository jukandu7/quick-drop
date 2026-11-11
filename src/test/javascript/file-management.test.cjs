const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/dashboard-files.js', 'utf8');
for (const action of ['deleteFiles', 'revokeDownloadLinks']) {
    test(`${action} confirmation names selected files and cancellation sends no request`, async () => {
        let message = '', calls = 0;
        const context = vm.createContext({
            fileActionInProgress: false,
            window: {confirm(text) { message = text; return false; }},
            fetch() { calls++; throw Error('Must not send a cancelled action'); }
        });
        vm.runInContext(source, context);
        await context[action]([{value: 'a', dataset: {filename: 'report.txt'}}, {value: 'b', dataset: {filename: 'photo.png'}}]);
        assert.match(message, /2 selected files/);
        assert.match(message, /report.txt/);
        assert.match(message, /photo.png/);
        assert.match(message, action === 'deleteFiles' ? /cannot be undone/ : /files will be kept/);
        assert.equal(calls, 0);
        assert.equal(context.fileActionInProgress, false);
    });
}
test('large selections show a bounded filename preview and the complete count', () => {
    const context = vm.createContext({});
    vm.runInContext(source, context);
    const choices = Array.from({length: 20}, (_, i) => ({dataset: {filename: `file-${i}.txt`}}));
    const description = context.describeFileSelection(choices);
    assert.match(description, /20 selected files/);
    assert.match(description, /file-4.txt/);
    assert.doesNotMatch(description, /file-5.txt/);
    assert.match(description, /15 more/);
});

test('clear selection clears partial choices and returns focus to selection controls', () => {
    const choices = [{checked: true}, {checked: false}, {checked: true}];
    let updated = 0, focused = 0;
    const context = vm.createContext({fileActionInProgress: false,
        filesList: {querySelectorAll: () => choices},
        selectAllButton: {focus() {focused++;}}
    });
    vm.runInContext(source, context);
    context.updateFileControls = () => {updated++;};
    context.clearFileSelection();
    assert.equal(choices.some(choice => choice.checked), false);
    assert.equal(updated, 1);
    assert.equal(focused, 1);
    context.fileActionInProgress = true;
    choices[0].checked = true;
    context.clearFileSelection();
    assert.equal(choices[0].checked, true);
    assert.equal(updated, 1);
});
