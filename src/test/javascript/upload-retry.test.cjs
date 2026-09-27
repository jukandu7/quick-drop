const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../main/resources');
const batchSource = fs.readFileSync(path.join(resources, 'static/js/upload-batch.js'), 'utf8');

function batchClass() {
    return vm.runInNewContext(batchSource + '\nUploadBatch;');
}

function file(name, size = 1024) {
    return {name, size};
}

test('a batch snapshots its files and upload options', () => {
    const UploadBatch = batchClass();
    const files = [file('a.txt'), file('b.txt')];
    const options = {expiration: '1h'};
    const batch = new UploadBatch(files, options);
    options.expiration = '7d';
    files.pop();

    assert.equal(batch.entries.length, 2);
    assert.equal(batch.options.expiration, '1h');
    assert.equal(batch.entries[0].filename, 'a.txt');
    assert.equal(batch.entries[0].status, 'pending');
    assert.equal(batch.entries[0].message, '');
    assert.equal(batch.entries[0].retryable, false);
    assert.equal(batch.retryableEntries().length, 0);
    assert.equal(batch.issues().length, 0);
    assert.equal(batch.hasFailures(), false);
});

test('18 successes and 2 temporary failures retry only those same 2 entries', () => {
    const UploadBatch = batchClass();
    const batch = new UploadBatch(Array.from({length: 20}, (_, i) => file(`file-${i}.txt`)));
    batch.entries.forEach((entry, i) => batch.finish(entry, i < 18
        ? {success: true}
        : {success: false, retryable: true, message: 'Connection interrupted'}));

    assert.equal(batch.summary(), '18 uploaded, 2 failed.');
    assert.equal(batch.hasFailures(), true);
    assert.deepEqual(Array.from(batch.retryableEntries(), entry => entry.filename), ['file-18.txt', 'file-19.txt']);
    assert.deepEqual(Array.from(batch.issues(), entry => entry.filename), ['file-18.txt', 'file-19.txt']);
    assert.ok(batch.entries.slice(0, 18).every(entry => entry.file == null));
    assert.ok(batch.retryableEntries().every(entry => entry.file));

    for (const entry of batch.retryableEntries()) {
        batch.finish(entry, {success: true});
    }

    assert.equal(batch.summary(), '20 uploaded, 0 failed.');
    assert.equal(batch.entries.length, 20);
    assert.equal(batch.issues().length, 0);
    assert.equal(batch.retryableEntries().length, 0);
    assert.equal(batch.hasFailures(), false);
    assert.ok(batch.entries.every(entry => entry.file == null));
});

test('repeated failures update one record, and skip/permanent rejection release file references', () => {
    const UploadBatch = batchClass();
    const batch = new UploadBatch([file('network.txt'), file('duplicate.txt'), file('oversized.txt')]);
    const [network, skipped, permanent] = batch.entries;
    batch.finish(network, {success: false, retryable: true, message: 'Network error'});
    batch.finish(network, {success: false, retryable: true, message: 'Still unavailable'});
    batch.finish(skipped, {success: false, skipped: true, message: 'Skipped'});
    batch.finish(permanent, {success: false, retryable: false, message: 'Too large'});

    assert.equal(batch.summary(), '0 uploaded, 2 failed, 1 skipped.');
    assert.equal(batch.issues().length, 3);
    assert.equal(network.message, 'Still unavailable');
    assert.deepEqual(Array.from(batch.retryableEntries(), entry => entry.filename), ['network.txt']);
    assert.equal(skipped.status, 'skipped');
    assert.equal(permanent.status, 'failed');
    assert.ok(skipped.file == null);
    assert.ok(permanent.file == null);

    batch.finish(network, {success: false, skipped: true, message: 'Skipped'});
    assert.equal(batch.summary(), '0 uploaded, 1 failed, 2 skipped.');
    assert.equal(batch.retryableEntries().length, 0);
    assert.ok(network.file == null);
});
