import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {importAttempt} from './import-vision-qualification.mjs';

const catalog = JSON.parse(readFileSync(new URL('../catalog/models.json', import.meta.url)));
const models = ['s', 'm'].map(quant => catalog.models.find(m => m.id === `second_state_llava_v1_5_7b_gguf_q4_k_${quant}`));
const report = `https://raw.githubusercontent.com/integrallis/models/${'e'.repeat(40)}/results/attempt.json`;
function fixture() {
  return {schema:'llava-catalog-attempt-v1', receipt: {
    revision:'a'.repeat(40), threads:8, context:1024, maximumOutputTokens:32, kvType:'f32',
    corpusSha256:'b'.repeat(64), artifacts:Object.fromEntries(models.flatMap(m=>m.files.map(f=>[f.path,f.sha256]))),
  }, confirmationIntegration:{passed:true}, candidates:['Q4_K_S','Q4_K_M'].map(variant=>({
    variant, backendQualified:true, gates:{accuracyLoss:true,vqaFloor:true,textVqaFloor:true,
      imageContribution:true,tokenAgreement:true,performance:true,strongestReferencePerformance:true,integrationConfirmation:true},
    datasets:Object.fromEntries(['vqav2','textvqa'].map(d=>[d,{cases:32,blankCases:8,candidateScore:.75,referenceScore:.75,imageContribution:.2}])),
    tokenAgreement:1, performance:['vqav2','textvqa'].flatMap(dataset=>[0,1].map(i=>({id:`${dataset}-${i}`,dataset,
      warmups:1,repetitions:3,worstRatios:{prefillMs:1,decodeMs:1,totalMs:1},passed:true,generationLengthsMatch:true}))),
  }))};
}
const bytes = value => Buffer.from(JSON.stringify(value));

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
