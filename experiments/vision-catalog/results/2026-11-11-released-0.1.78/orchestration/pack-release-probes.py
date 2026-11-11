#!/usr/bin/env python3
"""Package explicitly unpublished markers for the released SDK consumer repeat."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('source', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('models_version')
args = parser.parse_args()
source, output = args.source.resolve(), args.output.resolve()
revision = subprocess.check_output(['git', '-C', source, 'rev-parse', 'HEAD'], text=True).strip()
changed = subprocess.check_output(['git', '-C', source, 'diff', '--name-only', 'HEAD'], text=True).splitlines()
assert changed == ['catalog/vision-qualifications.json'], 'Only the unpublished probe manifest may differ'
manifest = source / 'catalog/vision-qualifications.json'
entries = json.loads(manifest.read_text())['entries']
original = json.loads(subprocess.check_output(['git', '-C', source, 'show',
    'HEAD:catalog/vision-qualifications.json'], text=True))['entries']
assert len(entries) == 2
probe_fields = {'backendVersion', 'releasedRuntimeVerified', 'modeljarsRuntimeVerified',
                'modeljarsVerification', 'releaseVerification'}
assert [{k:v for k,v in e.items() if k not in probe_fields} for e in entries] == [
    {k:v for k,v in e.items() if k not in probe_fields} for e in original], 'Measured evidence changed'
output.mkdir(parents=True, exist_ok=False)
(output / 'measured-markers').mkdir()
shutil.copyfile(manifest, output / 'unpublished-probe-manifest.json')
shutil.copyfile(source / 'experiments/vision-catalog/PreviewVision.java', output / 'PreviewVision.java')
for entry in entries:
    assert entry['backendVersion'] == args.models_version and entry['qualified']
    assert not entry['releasedRuntimeVerified'] and not entry['modeljarsRuntimeVerified']
    variant = 'q4_k_s' if entry['modelId'].endswith('_s') else 'q4_k_m'
    name = f'second-state.llava-v1.5-7b-gguf.{variant}-1.0.0-{variant}.1.jar'
    marker = output / 'measured-markers' / name
    shutil.copyfile(source / 'modeljars-catalog/build/libs/markers' / name, marker)
    with zipfile.ZipFile(marker) as archive:
        text = archive.read('META-INF/modeljars/vision-qualifications-v1.properties').decode()
    values = dict(line.split('=', 1) for line in text.splitlines() if '=' in line)
    assert values['modeljars.vision.count'] == '1'
    for key in ('modelId', 'backendVersion', 'reportSha256'):
        assert values['vision.0.' + key] == entry[key]
    assert values['vision.0.qualified'] == 'true'
receipt = dict(schema='modeljars-vision-unpublished-release-probes-v1', sourceRevision=revision,
               modelsVersion=args.models_version, markerPublished=False,
               purpose='Test actual released SDK runtime before official catalog admission or marker publication',
               files={str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in sorted(output.rglob('*')) if p.is_file()})
(output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
archive = output.with_name(output.name + '.tar.gz')
assert not archive.exists()
with tarfile.open(archive, 'w:gz') as tar:
    tar.add(output, arcname=output.name)
print(json.dumps(dict(archive=str(archive), sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                     sourceRevision=revision, markerPublished=False), indent=2))
