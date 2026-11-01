import { readFile } from 'node:fs/promises';
import { registryProperties } from './classification-qualifications.mjs';
import { filterQualifiedPublications } from './model-publications.mjs';

const [manifestPath,catalogPath]=process.argv.slice(2);
if (!manifestPath || !catalogPath) throw new Error('usage: classification-build-metadata.mjs MANIFEST CATALOG');
const manifest=JSON.parse(await readFile(manifestPath,'utf8'));
const catalog=JSON.parse(await readFile(catalogPath,'utf8'));
const empty={schemaVersion:1,entries:[]};
// Reuse the production admission and artifact-binding validator, including rejected entries.
filterQualifiedPublications({publications:[]},catalog,empty,empty,empty,empty,empty,empty,empty,manifest);
const properties=registryProperties(manifest);
process.stdout.write(JSON.stringify({properties,entries:manifest.entries.map(entry=>({entry,
  properties:registryProperties({...manifest,entries:[entry]})}))}));
