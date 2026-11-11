const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/upload-batch.js'), 'utf8');

function element() {
    return {dataset: {}, children: [], attributes: {}, hidden: false, textContent: '',
        appendChild(child) { this.children.push(child); },
        setAttribute(name, value) { this.attributes[name] = value; },
        removeAttribute(name) { delete this.attributes[name]; if (name === 'value') delete this.value; }
    };
}
function fixture() {
    const {UploadFeedback, UploadBatch} = vm.runInNewContext(source + '\n({UploadFeedback, UploadBatch})', {
        document: {createElement: element}
    });
    const fields = {strong: element(), small: element(), progress: element()};
    const row = element();
    row.querySelector = name => fields[name];
    return {UploadFeedback, UploadBatch, row, fields, queue: element()};
}

test('sending remains unconfirmed at 100%, and unknown progress is indeterminate', () => {
    const {UploadFeedback: ui, row, fields} = fixture();
    ui.prepare(row, {name: 'video.mp4'}, '20 MB');
    assert.equal(fields.progress.attributes['aria-label'], 'Sending video.mp4');
    ui.progress(row, {lengthComputable: true, loaded: 99.9, total: 100});
    assert.equal(row.dataset.state, 'sending');
    assert.equal(fields.progress.value, 99);
    ui.progress(row, {lengthComputable: true, loaded: 100, total: 100});
    assert.equal(row.dataset.state, 'saving');
    assert.equal(fields.progress.value, undefined);
    assert.match(fields.small.textContent, /waiting for confirmation/);
    ui.prepare(row, {name: 'next.mp4'}, '30 MB');
    ui.progress(row, {lengthComputable: false});
    assert.equal(row.dataset.state, 'sending');
    assert.equal(fields.progress.value, undefined);
});

test('mixed results include successful files, expand issues, and preserve filenames as text', () => {
    const {UploadFeedback: ui, UploadBatch, row, fields, queue} = fixture();
    const batch = new UploadBatch([{name: '<img src=x>'}, {name: 'retry.txt'}, {name: 'skip.txt'}]);
    batch.finish(batch.entries[0], {success: true});
    batch.finish(batch.entries[1], {success: false, retryable: true, message: 'Connection lost'});
    batch.finish(batch.entries[2], {skipped: true, message: 'Not replaced'});
    ui.finish(row, batch, queue);
    assert.equal(row.dataset.state, 'attention');
    assert.match(fields.small.textContent, /1 file confirmed/);
    assert.match(fields.small.textContent, /Retry is available for 1/);
    const details = queue.children[0];
    assert.equal(details.open, true);
    const results = details.children[1].children;
    assert.equal(results.length, 3);
    assert.equal(results[0].children[0].textContent, '<img src=x>');
    assert.match(results[0].children[1].textContent, /confirmed by Quick Drop/);
});

test('all skipped is not labeled successful; successful retries replace failed results', () => {
    const {UploadFeedback: ui, UploadBatch, row, fields, queue} = fixture();
    const batch = new UploadBatch([{name: 'one.txt'}]);
    batch.finish(batch.entries[0], {skipped: true});
    ui.finish(row, batch, queue);
    assert.equal(row.dataset.state, 'skipped');
    assert.match(fields.strong.textContent, /skipped/);
    const retry = new UploadBatch([{name: 'retry.txt'}]);
    retry.finish(retry.entries[0], {success: false, retryable: true});
    retry.finish(retry.entries[0], {success: true});
    ui.finish(row, retry, queue);
    assert.equal(row.dataset.state, 'complete');
    assert.equal(queue.children[1].open, false);
    assert.equal(queue.children[1].children[1].children.length, 1);
});
