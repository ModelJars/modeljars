/** Bind catalog claims to the exact recorded backend and consumer reports. */
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {isDeepStrictEqual} from 'node:util';
import {pathToFileURL} from 'node:url';
import {importAttempt} from './import-vision-qualification.mjs';
import {validateManifest} from './vision-qualifications.mjs';

const require = (condition, message) => { if (!condition) throw new Error(message); };
const binding = files => files.map(({path,role,sha256,sizeBytes}) => ({path,role,sha256,sizeBytes}))
  .sort((a,b) => a.path.localeCompare(b.path));

function consumer(report, entry, model) {
  require(report.schema === 'modeljars-vision-consumer-v1' && report.passed === true
    && report.marker === model.markerCoordinate, 'Vision consumer did not pass for this marker');
  require(isDeepStrictEqual(binding(report.verifiedFiles), binding(entry.files)), 'Consumer installed different files');
  require(report.modelsEvidenceVersion === entry.backendVersion, 'Consumer used different backend evidence');
  require(report.diagnostics?.['vision-projections']?.startsWith('rust-')
    && report.diagnostics['decoder-attention'] === 'NativeCausalAttention', 'Consumer used an unqualified execution plan');
  require(report.runtimeProperties && Object.keys(report.runtimeProperties)
    .every(k => ['models.native.kernels.library','modeljars.cache'].includes(k)), 'Consumer did not use default execution settings');
  const vqa = entry.performance.find(p => p.dataset === 'vqav2').caseId;
  const text = entry.performance.find(p => p.dataset === 'textvqa').caseId;
  require(report.cases?.length === 3 && report.cases.map(c=>c.id).join() === [vqa,text,vqa].join()
    && report.cases.every(c => c.matchesMeasuredOutput === true && c.answer === c.expected),
  'Consumer does not cover the frozen questions and reset repeat');
}

async function releasedArtifacts(release, loadArtifact) {
  require(/^[0-9]+\.[0-9]+\.[0-9]+$/.test(release.modeljarsVersion ?? '')
    && /^[0-9]+\.[0-9]+\.[0-9]+$/.test(release.vectorsVersion ?? ''), 'Missing released dependency versions');
  require(Object.keys(release.consumer.runtimeProperties).every(k => k === 'modeljars.cache'),
    'Released consumer must use its bundled native library');
  const expected = [
    ['modeljars','org/modeljars','modeljars',release.modeljarsVersion],
    ['modelsApi','com/integrallis','models-api',release.modelsVersion],
    ['javaBackend','com/integrallis','backend-java',release.modelsVersion],
    ['nativeBackend','com/integrallis','backend-native',release.modelsVersion],
    ['vectors','com/integrallis','vectors-core',release.vectorsVersion],
  ];
  require(release.artifacts?.length === expected.length
    && release.consumer.runtimeJars?.length === expected.length, 'Incomplete released runtime provenance');
  for (const [id,group,artifact,version] of expected) {
    const published = release.artifacts.filter(a => a.id === id);
    const loaded = release.consumer.runtimeJars.filter(a => a.id === id);
    const fileName = `${artifact}-${version}.jar`;
    require(published.length === 1 && loaded.length === 1
      && published[0].url === `https://repo.maven.apache.org/maven2/${group}/${artifact}/${version}/${fileName}`
      && loaded[0].fileName === fileName && /^[a-f0-9]{64}$/.test(published[0].sha256)
      && loaded[0].sha256 === published[0].sha256, 'Loaded runtime differs from released coordinates');
    const bytes = await loadArtifact(published[0].url);
    require(createHash('sha256').update(bytes).digest('hex') === published[0].sha256,
      'Loaded runtime differs from Maven Central bytes');
  }
}

export async function verifyVisionEvidence(manifest, catalog, loadReport) {
  validateManifest(manifest, catalog);
  const downloaded = new Map();
  async function download(url) {
    if (!downloaded.has(url)) downloaded.set(url, await loadReport(url));
    return downloaded.get(url);
  }
  async function load(reference) {
    const bytes = await download(reference.report);
    require(createHash('sha256').update(bytes).digest('hex') === reference.reportSha256, 'Vision report digest mismatch');
    return bytes;
  }
  for (const entry of manifest.entries) {
    const raw = await load(entry);
    const derived = importAttempt(raw, entry.report, catalog).entries.find(e => e.modelId === entry.modelId);
    for (const field of ['files','corpusSha256','datasets','tokenAgreement','performance','integrationPassed','qualified'])
      require(isDeepStrictEqual(entry[field], derived[field]), `Catalog vision ${field} differs from its measured report`);
    if (!entry.releasedRuntimeVerified)
      require(entry.backendVersion === derived.backendVersion, 'Source evidence cannot be relabeled as a release');
    const model = catalog.models.find(m=>m.id === entry.modelId);
    if (entry.modeljarsRuntimeVerified) consumer(JSON.parse(await load(entry.modeljarsVerification)), entry, model);
    if (entry.releasedRuntimeVerified) {
      const release = JSON.parse(await load(entry.releaseVerification));
      require(release.schema === 'modeljars-vision-release-v1' && release.passed === true
        && release.modelsVersion === entry.backendVersion, 'Released vision runtime was not verified');
      consumer(release.consumer, entry, model);
      await releasedArtifacts(release, download);
    }
  }
  return manifest.entries.length;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const count = await verifyVisionEvidence(JSON.parse(await readFile('catalog/vision-qualifications.json')),
    JSON.parse(await readFile('catalog/models.json')), async url => {
      const response = await fetch(url, {signal:AbortSignal.timeout(60000)});
      if (!response.ok) throw new Error(`Vision evidence HTTP ${response.status}: ${url}`);
      return Buffer.from(await response.arrayBuffer());
    });
  console.log(`Verified ${count} vision evidence records against their immutable reports.`);
}
