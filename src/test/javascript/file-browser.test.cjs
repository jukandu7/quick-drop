const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
function browser(files) {
    const rows = files.map(([filename, expiresAt, checked]) => {
        const row = {hidden: false, dataset: {expiresAt}, classList: {toggle(name, value) { row.selected = value; }}};
        const choice = {dataset: {filename}, checked: !!checked, closest: () => row};
        row.choice = choice;
        return row;
    });
    const document = {activeElement: null};
    let moves = 0;
    const context = vm.createContext({
        document,
        filesList: {querySelectorAll: () => rows.map(row => row.choice), contains(element) { return rows.some(row => row.choice === element); }, appendChild(row) { moves++; document.activeElement = null; rows.splice(rows.indexOf(row), 1); rows.push(row); }},
        fileSearch: {value: ''}, fileSort: {value: 'name'}, fileResults: {}, noMatches: {},
    });
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/dashboard-files.js', 'utf8'), context);
    return {context, rows, document, moves: () => moves, apply: () => vm.runInContext('applyFileFilter()', context)};
}
test('filter clears hidden selections and selected actions only include visible files', () => {
    const {context, rows, apply} = browser([['Alpha.zip', '', true], ['beta.txt', '', true]]);
    context.fileSearch.value = ' ALPHA ';
    apply();
    assert.equal(rows[1].hidden, true);
    assert.equal(rows[1].choice.checked, false);
    assert.equal(rows[0].selected, true);
    assert.equal(vm.runInContext('getSelectedFileChoices().length', context), 1);
    assert.equal(context.fileResults.textContent, '1 of 2 files');
    context.fileSearch.value = 'missing'; apply();
    assert.equal(context.noMatches.hidden, false);
    context.fileSearch.value = ''; apply();
    assert.equal(rows.some(row => row.choice.checked), false);
});
test('sort uses actual expiration, puts no expiry last, and supports natural filename order', () => {
    const {context, rows, apply} = browser([['file10', '', true], ['file2', '2027-01-01'], ['file1', '2026-12-01']]);
    apply();
    assert.deepEqual(rows.map(row => row.choice.dataset.filename), ['file1', 'file2', 'file10']);
    context.fileSort.value = 'name-desc'; apply();
    assert.deepEqual(rows.map(row => row.choice.dataset.filename), ['file10', 'file2', 'file1']);
    context.fileSort.value = 'expiration'; apply();
    assert.deepEqual(rows.map(row => row.choice.dataset.filename), ['file1', 'file2', 'file10']);
    assert.equal(rows[2].choice.checked, true);
});

test('selection and unchanged refresh preserve keyboard focus without moving rows', () => {
    const {rows, document, moves, apply} = browser([['a', '', false], ['b', '', false]]);
    document.activeElement = rows[0].choice;
    rows[0].choice.checked = true;
    apply(); apply();
    assert.equal(moves(), 0);
    assert.equal(document.activeElement, rows[0].choice);
});
test('a changed sort restores focus to the same file control', () => {
    const {rows, document, apply} = browser([['b', '', false], ['a', '', false]]);
    const focused = rows[0].choice;
    focused.focus = () => { document.activeElement = focused; };
    document.activeElement = focused;
    apply();
    assert.equal(document.activeElement, focused);
});
