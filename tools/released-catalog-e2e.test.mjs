import test from 'node:test';
import assert from 'node:assert/strict';
import { probeFor, program } from './released-catalog-e2e.mjs';

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
