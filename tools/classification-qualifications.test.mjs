import test from 'node:test';
import assert from 'node:assert/strict';
import { qualityMetrics, cohortCases, shapes, validateEntry, registryProperties } from './classification-qualifications.mjs';
const fixture = () => ({modelId:'fixture',backend:'rust-ffm',backendVersion:'models-0.0.0',report:'https://example.invalid/report',reportSha256:'a'.repeat(64),
  files:['model.safetensors','tokenizer.json'].map(p => ({path:p,role:'fixture',downloadUri:`https://example.invalid/${p}`,sha256:'a'.repeat(64),sizeBytes:12})),
  quality:Object.fromEntries(qualityMetrics.map(k=>[k,.5])),oracleQuality:Object.fromEntries(qualityMetrics.map(k=>[k,.5])),maximumOracleQualityLoss:0,cohortCases:{...cohortCases},inferenceErrors:0,
  numericalParityPassed:true,releasedRuntimeVerified:true,qualified:true,
  performance:[1,2,3].flatMap(pair => shapes.map(shape => ({pair,shape,samples:30,throughputRatio:1,p95LatencyRatio:1}))),
});
test('complete frozen gates permit serialization; empty registry admits nobody',() => {
  assert.equal(validateEntry(fixture()).qualified,true);
  assert.match(registryProperties({schemaVersion:2,policy:'schema-classification-upstream-v2',entries:[]}),/count=0/);
});
test('one failed shape, missing replication, failed quality or unreleased runtime cannot be admitted',() => {
  for (const mutate of [e => e.performance.pop(), e => e.performance[0].throughputRatio=.799,
    e => {e.quality['goemotions.microF1']=.494;e.maximumOracleQualityLoss=.006;}, e => e.releasedRuntimeVerified=false,
    e => e.performance=e.performance.filter(b => b.pair!==3),e => e.performance.push(e.performance[0])]) {
    const entry=fixture();mutate(entry);assert.throws(() => validateEntry(entry));
  }
});
test('malformed identities and nonfinite results fail even for rejected records',() => {
  for (const mutate of [e => e.files[0].path='../outside', e => e.files[0].sha256='bad',
    e => e.quality['goemotions.microF1']=NaN,e => delete e.quality['goemotions.microF1']]) {
    const entry=fixture();entry.qualified=false;mutate(entry);assert.throws(() => validateEntry(entry));
  }
});

test('publication filters bind every classification file and enforce measured gates', async () => {
  const { filterQualifiedPublications, selectCatalogPublications } = await import('./model-publications.mjs');
  const entry=fixture();
  const model={id:'fixture',name:'Synthetic classifier',markerCoordinate:'org.example:classifier:1.0.0',
    revision:'a'.repeat(40),sha256:'a'.repeat(64),sizeBytes:12,architecture:'gliner2',format:'safetensors',
    capabilities:['text-classification'],backends:{'rust-ffm':true},
    files:entry.files.map(({downloadUri,...file}) => file)};
  const catalog={schemaVersion:2,models:[model]},empty={schemaVersion:1,entries:[]};
  const planned=selectCatalogPublications(catalog,['all']);
  const filter=(e,c=catalog) => filterQualifiedPublications(planned,c,empty,empty,empty,empty,empty,empty,empty,{schemaVersion:2,policy:'schema-classification-upstream-v2',entries:[e]});
  assert.equal(filter(entry).publications.length,1);
  assert.throws(()=>filterQualifiedPublications(planned,catalog,empty,empty,empty,empty,empty,empty,empty,
    {schemaVersion:2,policy:'unknown-policy',entries:[entry]}),/Unsupported classification admission policy/);
  assert.equal(filter({...entry,qualified:false}).publications.length,0);
  const changed=structuredClone(catalog);changed.models[0].files[1].sha256='b'.repeat(64);
  assert.throws(() => filter(entry,changed),/snapshot/);
  for (const patch of [{sha256:'b'.repeat(64)},{sizeBytes:13}]) {
    const changedPrimary=structuredClone(catalog);Object.assign(changedPrimary.models[0],patch);
    assert.throws(() => filter(entry,changedPrimary),/primary weights/);
  }
  const failed=structuredClone(entry);failed.performance[0].throughputRatio=.7;
  assert.throws(() => filter(failed),/performance/);
});

test('classification-only admission reaches both manual and delta publication plans', async () => {
  const {mkdtemp,writeFile,rm}=await import('node:fs/promises');
  const {tmpdir}=await import('node:os');
  const path=await import('node:path');
  const {spawnSync}=await import('node:child_process');
  const folder=await mkdtemp(path.join(tmpdir(),'classification-plan-'));
  try {
    const entry=fixture(),empty={schemaVersion:1,entries:[]};
    const catalog={schemaVersion:2,models:[{id:'fixture',name:'Synthetic classifier',
      markerCoordinate:'org.example:classifier:1.0.0',revision:'a'.repeat(40),sha256:'a'.repeat(64),sizeBytes:12,
      architecture:'gliner2',format:'safetensors',capabilities:['text-classification'],backends:{'rust-ffm':true},
      files:entry.files.map(({downloadUri,...file})=>file)}]};
    for (const [name,body] of Object.entries({catalog,empty,emptyClassification:{schemaVersion:2,entries:[]},classification:{schemaVersion:2,policy:'schema-classification-upstream-v2',entries:[entry]}}))
      await writeFile(path.join(folder,name+'.json'),JSON.stringify(body));
    const built=spawnSync(process.execPath,['tools/classification-build-metadata.mjs',path.join(folder,'classification.json'),path.join(folder,'catalog.json')],{encoding:'utf8'});
    assert.equal(built.status,0,built.stderr);
    const metadata=JSON.parse(built.stdout);
    assert.equal(metadata.entries.length,1);
    assert.match(metadata.properties,/modeljars.classification.schemaVersion=2/);
    assert.match(metadata.entries[0].properties,/classification.0.oracleQuality.goemotions.microF1=0.5/);
    const args=['tools/plan-model-publications.mjs','--current',path.join(folder,'catalog.json'),
      '--qualifications',path.join(folder,'empty.json'),'--classification-qualifications',path.join(folder,'classification.json')];
    for (const mode of [['--ids','all'],['--previous',path.join(folder,'catalog.json'),
        '--previous-qualifications',path.join(folder,'empty.json'),
        '--previous-classification-qualifications',path.join(folder,'emptyClassification.json')]]) {
      const result=spawnSync(process.execPath,[...args,...mode],{encoding:'utf8'});
      assert.equal(result.status,0,result.stderr);
      assert.deepEqual(JSON.parse(result.stdout).publications.map(p=>p.id),['fixture']);
    }
  } finally {await rm(folder,{recursive:true,force:true});}
});

test('low absolute scores qualify only when they preserve paired upstream accuracy',()=>{
  const entry=fixture();
  for (const k of qualityMetrics) entry.quality[k]=entry.oracleQuality[k]=.2;
  assert.equal(validateEntry(entry).qualified,true);
  entry.quality['goemotions.microF1']=.195;
  entry.maximumOracleQualityLoss=.005;
  assert.equal(validateEntry(entry).qualified,true);
  entry.quality['goemotions.microF1']=.194;
  assert.throws(()=>validateEntry(entry),/recorded oracle loss/);
  entry.maximumOracleQualityLoss=.006;
  assert.throws(()=>validateEntry(entry),/production gates/);
});
