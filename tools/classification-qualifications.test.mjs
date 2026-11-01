import test from 'node:test';
import assert from 'node:assert/strict';
import { qualityFloors, shapes, validateEntry, registryProperties } from './classification-qualifications.mjs';
const fixture = () => ({modelId:'fixture',backend:'rust-ffm',backendVersion:'models-0.0.0',report:'https://example.invalid/report',reportSha256:'a'.repeat(64),
  files:['model.safetensors','tokenizer.json'].map(p => ({path:p,role:'fixture',downloadUri:`https://example.invalid/${p}`,sha256:'a'.repeat(64),sizeBytes:12})),
  quality:{...qualityFloors},maximumOracleQualityLoss:0,maximumBaseQualityRegression:0,qualityCases:1529,inferenceErrors:0,
  numericalParityPassed:true,releasedRuntimeVerified:true,qualified:true,
  performance:[1,2,3].flatMap(pair => shapes.map(shape => ({pair,shape,samples:30,throughputRatio:1,p95LatencyRatio:1}))),
});
test('complete frozen gates permit serialization; empty registry admits nobody',() => {
  assert.equal(validateEntry(fixture()).qualified,true);
  assert.match(registryProperties({schemaVersion:1,policy:'schema-classification-v1',entries:[]}),/count=0/);
});
test('one failed shape, missing replication, failed quality or unreleased runtime cannot be admitted',() => {
  for (const mutate of [e => e.performance.pop(), e => e.performance[0].throughputRatio=.799,
    e => e.quality['fresh.goemotions.microF1']=.69, e => e.releasedRuntimeVerified=false,
    e => e.performance=e.performance.filter(b => b.pair!==3),e => e.performance.push(e.performance[0])]) {
    const entry=fixture();mutate(entry);assert.throws(() => validateEntry(entry));
  }
});
test('malformed identities and nonfinite results fail even for rejected records',() => {
  for (const mutate of [e => e.files[0].path='../outside', e => e.files[0].sha256='bad',
    e => e.quality['fresh.boolq.accuracy']=NaN,e => delete e.quality['fresh.boolq.accuracy']]) {
    const entry=fixture();entry.qualified=false;mutate(entry);assert.throws(() => validateEntry(entry));
  }
});
