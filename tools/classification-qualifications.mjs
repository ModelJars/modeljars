import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const domains = ['agent_handoff','banking_intent','benefits_request','clinic_request','document_type',
  'email_triage','news_topic','paper_field','product_feedback','restaurant_review','review_sentiment',
  'screen_tags','sports_recap','support_intent','support_topic','ticket_route','travel_request'];
export const qualityMetrics = Object.freeze(['publisher.singleHead.meanDomainExactMatch',
  'publisher.jointHead.meanDomainExactMatch','publisher.jointDocument.meanDomainExactMatch',
  'goemotions.microF1','goemotions.macroF1',...domains.flatMap(domain =>
    ['singleHead','jointHead','jointDocument'].map(mode => `publisher.${mode}.${domain}.exactMatch`))]);
export const cohortCases = Object.freeze({'publisher.singleHead':2900,'publisher.jointHead':1700,'goemotions.fullTest':5427});
export const shapes = [
  ...[32,128,256,512].flatMap(tokens => [2,8,32].filter(labels => tokens !== 32 || labels !== 32)
    .map(labels => `tokens${tokens}-labels${labels}-tasks1`)),
  'tokens128-labels8-tasks2','tokens512-labels8-tasks2',
];
const require = (condition, message) => { if (!condition) throw new Error(message); };
const finite = value => typeof value === 'number' && Number.isFinite(value);
const text = value => typeof value === 'string' && value.trim().length > 0;
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);

export function validateEntry(entry) {
  require(text(entry.modelId) && entry.backend === 'rust-ffm' && text(entry.backendVersion), 'invalid classification runtime identity');
  require(text(entry.report) && digest(entry.reportSha256), 'missing immutable report binding');
  new URL(entry.report);
  require(Array.isArray(entry.files) && entry.files.length >= 2, 'complete snapshot files required');
  const paths = new Set();
  for (const file of entry.files) {
    require(text(file.path) && !file.path.startsWith('/') && !file.path.includes('\\')
      && file.path.split('/').every(p => p && p !== '.' && p !== '..') && !paths.has(file.path), 'invalid or duplicate snapshot path');
    paths.add(file.path);
    require(text(file.role) && digest(file.sha256) && Number.isSafeInteger(file.sizeBytes) && file.sizeBytes > 0, 'invalid snapshot identity');
    new URL(file.downloadUri);
  }
  for (const name of ['quality','oracleQuality']) {
    require(entry[name] && Object.keys(entry[name]).sort().join() === [...qualityMetrics].sort().join(), 'quality cohort coverage differs');
    require(Object.values(entry[name]).every(v => finite(v) && v >= 0 && v <= 1), 'invalid quality metric');
  }
  const loss=Math.max(0,...qualityMetrics.map(key => entry.oracleQuality[key]-entry.quality[key]));
  require(finite(entry.maximumOracleQualityLoss) && Math.abs(entry.maximumOracleQualityLoss-loss)<1e-12,
    'recorded oracle loss differs from paired metrics');
  require(entry.cohortCases && Object.keys(entry.cohortCases).sort().join()===Object.keys(cohortCases).sort().join()
    && Object.entries(cohortCases).every(([key,count]) => entry.cohortCases[key]===count), 'incomplete evaluation cohorts');
  require(Number.isSafeInteger(entry.inferenceErrors) && entry.inferenceErrors>=0,'invalid error count');
  for (const name of ['qualified','releasedRuntimeVerified','numericalParityPassed']) require(typeof entry[name] === 'boolean', `missing ${name}`);
  require(Array.isArray(entry.performance), 'performance evidence required');
  const pairs = new Map();
  for (const bucket of entry.performance) {
    require(Number.isSafeInteger(bucket.pair) && bucket.pair >= 1 && text(bucket.shape)
      && Number.isSafeInteger(bucket.samples) && bucket.samples > 0
      && finite(bucket.throughputRatio) && bucket.throughputRatio > 0
      && finite(bucket.p95LatencyRatio) && bucket.p95LatencyRatio > 0, 'invalid performance measurement');
    const rows = pairs.get(bucket.pair) ?? new Map();
    require(!rows.has(bucket.shape), 'duplicate performance bucket');
    rows.set(bucket.shape,bucket); pairs.set(bucket.pair,rows);
  }
  if (entry.qualified) {
    require(entry.releasedRuntimeVerified && entry.numericalParityPassed && entry.inferenceErrors === 0
      && entry.maximumOracleQualityLoss <= .005, 'classification production gates failed');
    require(pairs.size >= 3, 'three paired replications required');
    for (let pair=1;pair<=pairs.size;pair++) {
      const rows=pairs.get(pair);
      require(rows && [...rows.keys()].sort().join() === [...shapes].sort().join(), 'performance shape coverage differs');
      require([...rows.values()].every(r => r.samples >= 30 && r.throughputRatio >= .8 && r.p95LatencyRatio <= 1.5), 'performance bucket failed');
    }
  }
  return entry;
}

// Java Properties uses ISO-8859-1; explicitly escape Unicode and syntax-sensitive characters.
const escape = value => String(value).split('').map(ch => {
  const code=ch.charCodeAt(0);
  return code < 32 || code > 126 ? `\\u${code.toString(16).padStart(4,'0')}`
    : '\\:=#! '.includes(ch) ? `\\${ch}` : ch;
}).join('');
export function registryProperties(catalog) {
  require(catalog.schemaVersion === 2 && catalog.policy === 'schema-classification-upstream-v2'
    && Array.isArray(catalog.entries), 'unsupported classification registry schema');
  const output=['modeljars.classification.schemaVersion=2',`modeljars.classification.count=${catalog.entries.length}`];
  catalog.entries.forEach((raw,index) => {
    const entry=validateEntry(raw), prefix=`classification.${index}.`;
    const put=(key,value) => output.push(`${prefix}${escape(key)}=${escape(value)}`);
    for (const key of ['modelId','backend','backendVersion','report','reportSha256','maximumOracleQualityLoss',
      'inferenceErrors','numericalParityPassed','releasedRuntimeVerified','qualified']) put(key,entry[key]);
    for (const [key,value] of Object.entries(entry.quality).sort()) put(`quality.${key}`,value);
    for (const [key,value] of Object.entries(entry.oracleQuality).sort()) put(`oracleQuality.${key}`,value);
    for (const [key,value] of Object.entries(entry.cohortCases).sort()) put(`cohortCases.${key}`,value);
    put('files.count',entry.files.length);
    entry.files.forEach((file,i) => { for (const key of ['path','role','downloadUri','sha256','sizeBytes']) put(`files.${i}.${key}`,file[key]); });
    put('performance.count',entry.performance.length);
    entry.performance.forEach((bucket,i) => { for (const key of ['pair','shape','samples','throughputRatio','p95LatencyRatio']) put(`performance.${i}.${key}`,bucket[key]); });
  });
  return output.join('\n')+'\n';
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [input,output]=process.argv.slice(2);
  if (!input || !output) throw new Error('usage: classification-qualifications.mjs INPUT.json OUTPUT.properties');
  const text=registryProperties(JSON.parse(await readFile(input,'utf8')));
  await mkdir(path.dirname(output),{recursive:true}); await writeFile(output,text,'ascii');
}
