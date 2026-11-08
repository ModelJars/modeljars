import {readFile} from "node:fs/promises";
import {publishable, registryProperties, validateManifest} from "./vision-qualifications.mjs";

const [manifestPath, catalogPath] = process.argv.slice(2);
if (!manifestPath || !catalogPath) throw new Error("usage: vision-build-metadata.mjs MANIFEST CATALOG");
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
validateManifest(manifest, JSON.parse(await readFile(catalogPath, "utf8")));
process.stdout.write(JSON.stringify({entries: manifest.entries.map(entry => ({entry,
  publishable: publishable(entry), properties: registryProperties({...manifest, entries: [entry]})}))}));
