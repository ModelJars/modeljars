#!/usr/bin/env python3
"""Check catalog capabilities against the resolved Models JARs (no model inference)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def version(value):
    if not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise ValueError(f'Expected exact released Models version, got {value!r}')
    return tuple(map(int, value.split('.')))


def verify(policy, catalog, manifests, artifacts):
    if policy.get('schemaVersion') != 1:
        raise ValueError('Unsupported runtime support schema')
    models = {m['id']: m for m in catalog['models']}
    by_module = {a['module']: a for a in artifacts if a['group'] == 'com.integrallis'}
    backend = by_module.get('backend-java')
    failures = []
    if backend is None:
        return ['Resolved graph has no backend-java'], []
    resolved = version(backend['version'])
    if resolved < version(policy['minimumModelsVersion']):
        failures.append(f"Catalog support floor is Models {policy['minimumModelsVersion']}; resolved {backend['version']}")
    classes = {}
    for module, artifact in by_module.items():
        if module == 'models' or module.startswith(('models-', 'backend-')):
            if artifact['version'] != backend['version']:
                failures.append(f"Mixed Models runtime: {module}:{artifact['version']} vs {backend['version']}")
            with zipfile.ZipFile(artifact['file']) as jar:
                classes[module] = set(jar.namelist())
    receipts = []
    for manifest_name, requirements in policy['workloads'].items():
        for entry in manifests[manifest_name]['entries']:
            if not (entry.get('qualified') is True or entry.get('summary', {}).get('qualified') is True):
                continue
            model = models.get(entry['modelId'])
            if model is None:
                failures.append(f"{manifest_name}: unknown qualified model {entry['modelId']}")
                continue
            architecture = model['architecture']
            required = requirements['architectures'].get(architecture)
            if required is None:
                failures.append(f"{entry['modelId']}: no reviewed {manifest_name} runtime requirement for {architecture}")
                continue
            for requirement in [*requirements['classes'], *required]:
                module, class_name = requirement.split(':', 1)
                if class_name.replace('.', '/') + '.class' not in classes.get(module, set()):
                    failures.append(f"{entry['modelId']}: resolved runtime lacks {requirement}")
            if entry['backend'] not in requirements['backends']:
                failures.append(f"{entry['modelId']}: unsupported workload/backend {entry['backend']}")
            if entry['backend'] == 'rust-ffm' and 'com/integrallis/models/backend/nativekernel/RustFfmBackend.class' not in classes.get('backend-native', set()):
                failures.append(f"{entry['modelId']}: missing Rust/FFM runtime")
            receipts.append({'modelId': entry['modelId'], 'backend': entry['backend'],
                             'workload': manifest_name, 'architecture': architecture,
                             'modelsVersion': backend['version']})
    return failures, receipts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolved', type=Path, required=True)
    parser.add_argument('--catalog-dir', type=Path, default=Path('catalog'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)
    policy = json.loads((args.catalog_dir / 'runtime-support.json').read_text())
    catalog = json.loads((args.catalog_dir / 'models.json').read_text())
    manifests = {name: json.loads((args.catalog_dir / name).read_text()) for name in policy['workloads']}
    artifacts = json.loads(args.resolved.read_text())
    failures, entries = verify(policy, catalog, manifests, artifacts)
    if failures:
        raise SystemExit('\n'.join(failures))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'schemaVersion': 1, 'entries': entries,
        'inputs': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                   [args.catalog_dir / 'runtime-support.json', args.catalog_dir / 'models.json',
                    *[args.catalog_dir / name for name in manifests]]},
        'artifacts': [{**a, 'sha256': hashlib.sha256(Path(a['file']).read_bytes()).hexdigest()} for a in artifacts]}, indent=2) + '\n')
    print(f'{len(entries)} qualified workload/backend capabilities match the resolved runtime')


if __name__ == '__main__':
    main()
