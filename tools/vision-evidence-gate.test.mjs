import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import test from 'node:test';
import {importAttempt} from './import-vision-qualification.mjs';
import {verifyVisionEvidence} from './vision-evidence-gate.mjs';
import {catalog, models, report, fixture, bytes} from './test-fixtures/vision-attempt.mjs';

const hash = raw => createHash('sha256').update(raw).digest('hex');

test('verification fetches a shared report once and detects a fabricated catalog score', async () => {
  const raw=bytes(fixture()), manifest=importAttempt(raw,report,catalog);
  let reads=0;
  const load=async()=>{reads++;return raw;};
  assert.equal(await verifyVisionEvidence(manifest,catalog,load),2);
  assert.equal(reads,1);
  manifest.entries[0].datasets.vqav2.candidateScore=.76;
  await assert.rejects(verifyVisionEvidence(manifest,catalog,load),/differs from its measured report/);
});

test('tampered bytes cannot inherit a report digest', async () => {
  const raw=bytes(fixture()), manifest=importAttempt(raw,report,catalog);
  await assert.rejects(verifyVisionEvidence(manifest,catalog,async()=>Buffer.from('tampered')),/digest mismatch/);
});

test('consumer verification requires exact artifacts, three outputs and public defaults', async () => {
  const raw=bytes(fixture()), manifest=importAttempt(raw,report,catalog), entry=manifest.entries[0];
  const url=report.replace('attempt.json','consumer.json');
  const value={schema:'modeljars-vision-consumer-v1',passed:true,marker:models[0].markerCoordinate,
    modelsEvidenceVersion:entry.backendVersion,verifiedFiles:entry.files,runtimeProperties:{},
    diagnostics:{'vision-projections':'rust-matrixmultiply','decoder-attention':'NativeCausalAttention'},
    cases:['vqav2-0','textvqa-0','vqav2-0'].map(id=>({id,answer:'yes',expected:'yes',matchesMeasuredOutput:true}))};
  entry.modeljarsRuntimeVerified=true;
  async function verify(value) {
    const smoke=bytes(value);entry.modeljarsVerification={report:url,reportSha256:hash(smoke)};
    return verifyVisionEvidence(manifest,catalog,async u=>u===url?smoke:raw);
  }
  assert.equal(await verify(value),2);
  value.runtimeProperties['vectors.gguf.threads']='8';
  await assert.rejects(verify(value),/default execution settings/);
  value.runtimeProperties={};value.cases[2].answer='different';
  await assert.rejects(verify(value),/frozen questions/);
});
