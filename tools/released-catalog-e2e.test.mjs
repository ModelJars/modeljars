import test from 'node:test';
import assert from 'node:assert/strict';
import { markerPomUrl, probeFor, program, smallestOnCentralPerCapability } from './released-catalog-e2e.mjs';

test('schema classifiers use the public classifier even with typed-decision capability',()=>{
  const model={id:'fixture',markerCoordinate:'org.example:fixture:1.0.0',
    features:['schema-driven-classification'],capabilities:['typed-decision','text-classification']};
  const [capability,probe]=probeFor(model);
  assert.equal(capability,'schema-classification');
  const source=program(model,capability,probe,'0.1.74');
  assert.match(source,/ModelJars.openClassificationRuntime/);
  assert.match(source,/selected.equals/);
  assert.match(source,/-Dmodels.deberta.threads=8/);
  assert.doesNotMatch(source,/ModernBert|openRuntime\(/);
  assert.equal(probeFor({...model,features:[]})[0],'typed-decision');
});

test('marker POM URLs follow the Maven layout and refuse malformed coordinates', () => {
  assert.equal(
    markerPomUrl('org.modeljars.huggingface:sahilmahendrakar.paradee-8m-v1.2.int8:1.2.0-int8.1'),
    'https://repo1.maven.org/maven2/org/modeljars/huggingface/sahilmahendrakar.paradee-8m-v1.2.int8/1.2.0-int8.1/sahilmahendrakar.paradee-8m-v1.2.int8-1.2.0-int8.1.pom',
  );
  assert.throws(() => markerPomUrl('org.example:only-two'), /malformed/);
  assert.throws(() => markerPomUrl('a:b:c:d'), /malformed/);
});

test('compile-only selection skips a published marker Central does not serve yet', async () => {
  const model = (id, size, capability) => ({
    id, sizeBytes: size, capabilities: [capability], markerCoordinate: `org.example:${id}:1.0.0`,
  });
  const fresh = model('fresh-tts', 10, 'text-to-speech');
  const released = model('released-tts', 20, 'text-to-speech');
  const larger = model('larger-tts', 30, 'text-to-speech');
  const embedder = model('embedder', 5, 'text-embedding');
  const asked = [];
  const fetchImpl = async (url) => {
    asked.push(url);
    return { status: url.includes('/fresh-tts/') ? 404 : 200 };
  };
  const { chosen, pending } = await smallestOnCentralPerCapability(
    [larger, fresh, embedder, released], fetchImpl);
  assert.deepEqual(chosen.map((m) => m.id).sort(), ['embedder', 'released-tts']);
  assert.deepEqual(pending.map((m) => m.id), ['fresh-tts']);
  // Sorted by size and stopping at the first served marker: the larger one is never asked about.
  assert.equal(asked.some((url) => url.includes('/larger-tts/')), false);
});

test('compile-only selection stops on any Central answer other than 200 or 404', async () => {
  const only = { id: 'x', sizeBytes: 1, capabilities: ['text-to-speech'], markerCoordinate: 'org.example:x:1.0.0' };
  await assert.rejects(
    smallestOnCentralPerCapability([only], async () => ({ status: 503 })),
    /HTTP 503/,
  );
});
