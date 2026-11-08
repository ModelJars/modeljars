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

test('separate worker reports retain independent provenance and cannot be swapped', async () => {
  const reports=new Map(), entries=[];
  for (const [index,variant] of ['Q4_K_S','Q4_K_M'].entries()) {
    const attempt=fixture();
    attempt.candidates=[attempt.candidates[index]];
    attempt.receipt.variants=[variant];
    attempt.receipt.host=`worker-${variant}`;
    const raw=bytes(attempt), url=report.replace('attempt.json',`${variant}.json`);
    reports.set(url,raw);
    entries.push(...importAttempt(raw,url,catalog).entries);
  }
  const manifest={schemaVersion:1,policy:'llava-vqa-v1',entries};
  assert.equal(await verifyVisionEvidence(manifest,catalog,async u=>reports.get(u)),2);
  entries[0].report=entries[1].report;
  entries[0].reportSha256=entries[1].reportSha256;
  await assert.rejects(verifyVisionEvidence(manifest,catalog,async u=>reports.get(u)),/does not contain this model/);
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

test('released consumer binds loaded JARs to Central and forbids a native override', async () => {
  const raw=bytes(fixture()), manifest=importAttempt(raw,report,catalog), entry=manifest.entries[0];
  entry.backendVersion='0.5.15'; entry.releasedRuntimeVerified=true;
  const url=report.replace('attempt.json','released.json');
  const jars=new Map();
  const artifacts=[['modeljars','org/modeljars','modeljars','0.1.77'],
    ['modelsApi','com/integrallis','models-api','0.5.15'],
    ['javaBackend','com/integrallis','backend-java','0.5.15'],
    ['nativeBackend','com/integrallis','backend-native','0.5.15'],
    ['vectors','com/integrallis','vectors-core','0.1.33']].map(([id,group,name,version])=>{
      const url=`https://repo.maven.apache.org/maven2/${group}/${name}/${version}/${name}-${version}.jar`;
      const data=Buffer.from(`synthetic JAR bytes: ${id}`);jars.set(url,data);
      return {id,url,sha256:hash(data)};
    });
  const value={schema:'modeljars-vision-release-v1',passed:true,modelsVersion:'0.5.15',
    modeljarsVersion:'0.1.77',vectorsVersion:'0.1.33',artifacts,
    consumer:{schema:'modeljars-vision-consumer-v1',passed:true,marker:models[0].markerCoordinate,
      modelsEvidenceVersion:entry.backendVersion,verifiedFiles:entry.files,runtimeProperties:{},
      runtimeJars:artifacts.map(a=>({id:a.id,fileName:a.url.split('/').at(-1),sha256:a.sha256})),
      diagnostics:{'vision-projections':'rust-matrixmultiply','decoder-attention':'NativeCausalAttention'},
      cases:['vqav2-0','textvqa-0','vqav2-0'].map(id=>({id,answer:'yes',expected:'yes',matchesMeasuredOutput:true}))}};
  const cliUrl='https://repo.maven.apache.org/maven2/org/modeljars/modeljars-cli/0.1.77/modeljars-cli-0.1.77.jar';
  const cliBytes=Buffer.from('synthetic released CLI JAR');
  jars.set(cliUrl,cliBytes);
  value.cliGenerator={url:cliUrl,sha256:hash(cliBytes)};
  value.cliResolvedGraphSha256=hash(Buffer.from('synthetic CLI graph'));
  value.demoSha256=hash(Buffer.from('synthetic generated demo'));
  async function verify() {
    const release=bytes(value);
    entry.releaseVerification={report:url,reportSha256:hash(release),modelsVersion:'0.5.15'};
    return verifyVisionEvidence(manifest,catalog,async u=>u===url?release:jars.get(u)??raw);
  }
  assert.equal(await verify(),2);
  value.cliGenerator.url=cliUrl.replaceAll('0.1.77','0.1.65');
  await assert.rejects(verify(),/CLI demo provenance/);
  value.cliGenerator.url=cliUrl;
  jars.set(cliUrl,Buffer.from('different CLI JAR'));
  await assert.rejects(verify(),/Demo generator differs/);
  jars.set(cliUrl,cliBytes);
  value.consumer.runtimeProperties['models.native.kernels.library']='/tmp/source-build.so';
  await assert.rejects(verify(),/bundled native library/);
  value.consumer.runtimeProperties={};value.consumer.runtimeJars[0].sha256='0'.repeat(64);
  await assert.rejects(verify(),/released coordinates/);
  value.consumer.runtimeJars[0].sha256=artifacts[0].sha256;
  jars.set(artifacts[0].url,Buffer.from('different Central artifact'));
  await assert.rejects(verify(),/Maven Central bytes/);
});
