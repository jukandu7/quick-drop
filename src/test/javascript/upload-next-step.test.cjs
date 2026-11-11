const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const Batch = vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/upload-batch.js','utf8') + '\nUploadBatch');
test('confirmed uploads give distinct owner and guest next steps', () => {
    const batch = new Batch([{name: 'file.txt'}]);
    batch.finish(batch.entries[0], {success: true});
    assert.match(batch.nextStep(), /Your files.*download or share/);
    assert.match(batch.nextStep(false), /owner’s computer.*Let the owner know/);
});
test('failed and skipped batches never imply that files were saved', () => {
    const batch = new Batch([{name: 'file.txt'}]);
    batch.finish(batch.entries[0], {retryable: true});
    assert.match(batch.nextStep(), /Check whether interrupted files arrived.*Retry failed/);
    batch.finish(batch.entries[0], {retryable: false});
    assert.match(batch.nextStep(), /Review.*corrected files/);
    batch.finish(batch.entries[0], {skipped: true});
    assert.match(batch.nextStep(), /No new files were uploaded/);
});
