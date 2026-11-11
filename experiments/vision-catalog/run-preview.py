#!/usr/bin/env python3
"""Exercise measured preview markers through the public API, including admission refusals."""
import base64
import argparse
import hashlib
import json
import platform
import subprocess
import zipfile
from pathlib import Path


def read(path):
    return json.loads(path.read_text())


def run(command, directory, name):
    with (directory / (name + '.stdout.log')).open('w') as stdout, \
            (directory / (name + '.stderr.log')).open('w') as stderr:
        return subprocess.run(list(map(str, command)), stdout=stdout, stderr=stderr).returncode


def completed_attempt(root, native):
    campaign = root / 'campaign'
    for marker in [root / 'COLLECTION_COMPLETE', campaign / 'results/COLLECTION_COMPLETE']:
        if not marker.is_file():
            raise ValueError('Finish both-arm collection and admission evaluation first: ' + str(marker))
    report = read(campaign / 'results/admission-attempt.json')
    with native.open('rb') as stream:
        native_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    if native_sha != report['receipt']['nativeSha256']:
        raise ValueError('Preview native library differs from the measured backend')
    return campaign, report


def main():
    if platform.system() != 'Linux':
        raise SystemExit('Run on the remote Linux worker')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('payload', type=Path)
    parser.add_argument('--native-library', type=Path, required=True)
    args = parser.parse_args()
    root, payload, native = args.root.resolve(), args.payload.resolve(), args.native_library.resolve()
    campaign, report = completed_attempt(root, native)
    cases = read(campaign / 'cases.json')['cases']
    selected = [next(c for c in cases if c['dataset'] == dataset and not c['blank'])
                for dataset in ['vqav2', 'textvqa']]
    selected.append(selected[0])
    output = payload / 'consumer-results'
    output.mkdir(exist_ok=False)
    markers = sorted(p for p in (payload / 'measured-markers').glob('*.jar') if not p.name.startswith('._'))
    if len(markers) != 2:
        raise ValueError('Exactly two measured preview markers required')
    jars = sorted(p for p in (payload / 'lib').glob('*.jar') if not p.name.startswith('._'))
    expected = read(payload / 'receipt.json')['files']
    if not jars or any(not zipfile.is_zipfile(p) or hashlib.sha256(p.read_bytes()).hexdigest()
                       != expected[str(p.relative_to(payload))] for p in jars):
        raise ValueError('Preview classpath differs from its payload receipt')
    common = ['/opt/java/bin/java', '--add-modules=jdk.incubator.vector',
              '--enable-native-access=ALL-UNNAMED', '-Xmx12g',
              '-Dmodels.native.kernels.library=' + str(native),
              '-cp', ':'.join(map(str, jars + markers))]
    for candidate in report['candidates']:
        variant = candidate['variant']
        directory = output / variant
        directory.mkdir()
        cache = directory / 'cache'
        cache.mkdir()
        command = common + ['-Dmodeljars.cache=' + str(cache)]
        marker = 'org.modeljars.huggingface:second-state.llava-v1.5-7b-gguf.' + variant.lower() + ':1.0.0-' + variant.lower() + '.1'
        demo = payload / ('pr380-llava-q4k' + variant[-1].lower() + '-demo.java')
        receipt = dict(schema='modeljars-vision-preview-execution-v1', variant=variant,
                       backendQualified=candidate['backendQualified'],
                       harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                       modeljarsRevision=read(payload / 'receipt.json')['modeljarsRevision'],
                       markers={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in markers},
                       releasedRuntime=False)
        if candidate['backendQualified']:
            rows = []
            for case in selected:
                measured = read(campaign / 'results' / variant / 'candidate' / case['id'] / 'runs.json')
                answer = next(r['answer'] for r in measured if r['run'] == 0)
                encoded = [base64.b64encode(s.encode()).decode() for s in [case['question'], answer]]
                rows.append('\t'.join([case['id'], str(campaign / case['image']), *encoded]))
            tsv = directory / 'cases.tsv'
            tsv.write_text('\n'.join(rows) + '\n')
            status = run(command + [payload / 'PreviewVision.java', marker, tsv, directory / 'consumer.json'],
                         directory, 'consumer')
            if status != 0 or not read(directory / 'consumer.json')['passed']:
                raise ValueError('Public API consumer failed: ' + variant)
        status = run(command + [demo, campaign / selected[0]['image'], selected[0]['question']], directory, 'demo')
        receipt['demoExitCode'] = status
        if candidate['backendQualified']:
            expected = read(directory / 'consumer.json')['cases'][0]['answer']
            receipt['passed'] = status == 0 and ('Answer: ' + expected + '\n') in (directory / 'demo.stdout.log').read_text()
        else:
            reason = 'No qualified vision execution for ' + marker
            receipt['rejectionReason'] = reason
            receipt['cacheFiles'] = [str(p.relative_to(cache)) for p in cache.rglob('*') if p.is_file()]
            receipt['passed'] = status != 0 and reason in (directory / 'demo.stderr.log').read_text() and not receipt['cacheFiles']
        (directory / 'execution.json').write_text(json.dumps(receipt, indent=2) + '\n')
        if not receipt['passed']:
            raise ValueError('Public demo did not match the admission decision: ' + variant)
        print(variant + ': ' + ('public API and CLI PASS' if candidate['backendQualified'] else 'correctly refused before download'))


if __name__ == '__main__':
    main()
