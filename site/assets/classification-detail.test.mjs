import test from 'node:test';
import assert from 'node:assert/strict';
import { primaryQualification } from './qualification-data.js';
import { qualificationSummary, classificationJavaSnippet, resourceMemoryNote, renderModel } from './model-detail.js';
import { qualityMetrics, cohortCases, shapes } from '../../tools/classification-qualifications.mjs';

test('classification evidence renders task accuracy, paired performance and the classifier API',()=>{
  const entry={qualified:true,backend:'rust-ffm',backendVersion:'models-fixture',
    report:'https://example.invalid/report',reportSha256:'a'.repeat(64),
    quality:Object.fromEntries(qualityMetrics.map(k=>[k,.36])),oracleQuality:Object.fromEntries(qualityMetrics.map(k=>[k,.36])),
    maximumOracleQualityLoss:0,cohortCases,
    performance:[1,2,3].flatMap(pair=>shapes.map(shape=>({pair,shape,throughputRatio:1,p95LatencyRatio:1})))};
  const model={id:'fixture_classifier',name:'Fixture classifier',description:'Schema classification',
    markerCoordinate:'org.example:fixture:1.0.0',revision:'a'.repeat(40),sha256:'a'.repeat(64),sizeBytes:1000,
    format:'safetensors',architecture:'gliner2',backends:{'rust-ffm':true},capabilities:['text-classification','typed-decision'],
    features:['schema-driven-classification'],classificationQualifications:[entry]};
  const summary=qualificationSummary(primaryQualification(model));
  assert.equal(summary.requests,10027); assert.equal(summary.replications,3);
  assert.equal(summary.quality['goemotions.microF1'],.36);
  assert.match(classificationJavaSnippet(model.id),/ModelJars.openClassifier/);
  assert.match(resourceMemoryNote(model),/Schema classification working buffers/);
  const target={innerHTML:''}, meta={content:''};
  const previous=globalThis.document;
  globalThis.document={querySelector:selector=>selector==='#model-detail'?target:meta};
  try {renderModel(model,[model]);} finally {globalThis.document=previous;}
  assert.match(target.innerHTML,/GoEmotions full test micro-F1/);
  assert.match(target.innerHTML,/not calibrated confidence/);
  assert.match(target.innerHTML,/openClassifier/);
  assert.doesNotMatch(target.innerHTML,/TTFT p95|ModernBert|undefined-request|rag-evidence-title/);
});
