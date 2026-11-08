import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import test from 'node:test';
import {importAttempt} from './import-vision-qualification.mjs';

import {catalog, models, report, fixture, bytes} from './test-fixtures/vision-attempt.mjs';

test('import binds exact report bytes and runtime revision without authorizing publication', () => {
  const raw=bytes(fixture());
  const result=importAttempt(raw,report,catalog);
  assert.equal(result.entries.length,2);
  for(const e of result.entries) {
    assert.equal(e.reportSha256,createHash('sha256').update(raw).digest('hex'));
    assert.equal(e.backendVersion,`models-source:${'a'.repeat(40)}`);
    assert.equal(e.qualified,true);
    assert.equal(e.releasedRuntimeVerified,false);
    assert.equal(e.modeljarsRuntimeVerified,false);
  }
});

test('missing gates, wrong artifacts, changed controls and unmatched generation cannot import as qualified', () => {
  for(const mutate of [
    a=>delete a.candidates[0].gates.imageContribution,
    a=>a.candidates[0].gates.performance=false,
    a=>a.receipt.artifacts[models[0].files[1].path]='f'.repeat(64),
    a=>a.receipt.threads=16,
    a=>a.candidates[0].performance[0].generationLengthsMatch=false,
    a=>a.candidates[0].performance[0].worstRatios.prefillMs=1.11,
  ]) {
    const attempt=fixture();mutate(attempt);
    assert.throws(()=>importAttempt(bytes(attempt),report,catalog));
  }
  assert.throws(()=>importAttempt(bytes(fixture()),report.replace('e'.repeat(40),'main'),catalog),/pin/);
});

test('failed attempts remain importable as rejected evidence', () => {
  const attempt=fixture();
  attempt.confirmationIntegration.passed=false;
  for(const c of attempt.candidates) {c.gates.integrationConfirmation=false;c.backendQualified=false;}
  const result=importAttempt(bytes(attempt),report,catalog);
  assert.ok(result.entries.every(e=>!e.qualified && !e.integrationPassed));
});

test('a single-variant worker retains its own report binding and all admission gates', () => {
  const attempt=fixture();
  attempt.candidates=attempt.candidates.slice(1);
  attempt.receipt.variants=['Q4_K_M'];
  attempt.receipt.host='qualification-worker-m';
  const raw=bytes(attempt);
  const result=importAttempt(raw,report,catalog);
  assert.equal(result.entries.length,1);
  assert.equal(result.entries[0].modelId,models[1].id);
  assert.equal(result.entries[0].reportSha256,createHash('sha256').update(raw).digest('hex'));
  assert.equal(result.entries[0].qualified,true);
  assert.equal(result.entries[0].releasedRuntimeVerified,false);
  for(const mutate of [
    a=>delete a.receipt.variants,
    a=>a.receipt.variants=['Q4_K_S'],
    a=>a.receipt.variants=['Q4_K_M','Q4_K_M'],
    a=>a.receipt.host='',
    a=>delete a.candidates[0].gates.tokenAgreement,
  ]) {
    const invalid=structuredClone(attempt);mutate(invalid);
    assert.throws(()=>importAttempt(bytes(invalid),report,catalog));
  }
});

test('declared coverage cannot hide a missing variant or duplicate verdict', () => {
  for(const mutate of [
    a=>a.receipt.variants=['Q4_K_S'],
    a=>a.candidates.push(a.candidates[0]),
    a=>a.candidates=[],
    a=>a.candidates[1].variant='Q4_K_S',
  ]) {
    const attempt=fixture();attempt.receipt.host='worker';mutate(attempt);
    assert.throws(()=>importAttempt(bytes(attempt),report,catalog));
  }
});
