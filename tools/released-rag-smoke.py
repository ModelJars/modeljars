"""Measure a published runtime with CLI-verified weights, including gated snapshots."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument('--models-repo', type=Path, required=True)
parser.add_argument('--payload', type=Path, required=True)
parser.add_argument('--model-id', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[1]
models = args.models_repo.resolve()
payload = args.payload.resolve()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
for key in ['JAVA_TOOL_OPTIONS', 'JAVA_OPTS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']:
    if 'models.' in os.environ.get(key, ''):
        raise ValueError(f'{key} contains a Models tuning property')
manifest = models / 'benchmark-results/2026-10-11-bonsai-production/released-catalog-regression-jobs.json'
job = next(j for j in json.loads(manifest.read_text()) if j['id'] == args.model_id)
cli = repo / 'modeljars-cli/build/install/modeljars/bin/modeljars'
installed = subprocess.check_output([
    str(cli), '--cache', str(out / 'cache'), '--output', 'json', '--progress', 'off',
    'pull', job['id']], text=True)
installation = json.loads(installed)
artifact = Path(installation['path'])
directory = artifact.parent
verified = []
for entry in job['files']:
    path = directory / entry['path'] if job['format'] == 'safetensors' else artifact
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if path.stat().st_size != entry['sizeBytes'] or digest != entry['sha256']:
        raise ValueError(f'Installed bytes differ from the frozen input: {entry["path"]}')
    verified.append({**entry, 'verifiedSha256': digest, 'verifiedSizeBytes': path.stat().st_size})
provenance = json.loads((payload / 'provenance.json').read_text())
records = json.loads((payload / 'classpath.json').read_text())
for entry in records:
    path = payload / 'lib' / entry['file']
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        raise ValueError(f'Payload changed: {path.name}')
    if path.name.startswith(('models-', 'backend-', 'vectors-')) and not path.name.startswith('models-rag-bench-'):
        if entry['origin'] != 'Maven Central':
            raise ValueError(f'Product is not a downloaded release: {path.name}')
command = ['java', '--add-modules', 'jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED',
    '-cp', str(payload / 'lib/*'), 'com.integrallis.models.rag.RagBenchmarkCli',
    '--framework', 'plain-java', '--backend', job['backend'],
    '--backend-version', f'models@{provenance["modelsVersion"]}+{provenance["harnessRevision"]}',
    '--model', str(directory if job['format'] == 'safetensors' else artifact), '--model-id', job['id'],
    '--workload', job['wl'], '--prompt-template', job['tpl'], '--context', str(job['context']),
    '--threads', str(job['threads']), '--max-tokens', str(job['mt']), '--top-k', str(job['topK']),
    '--warmups', '0', '--iterations', '1', '--output', str(out / 'report.json')]
if job.get('stopSequence'):
    command.extend(['--stop-sequence', job['stopSequence']])
(out / 'inputs.json').write_text(json.dumps({'job': job, 'files': verified,
    'command': command, 'tuningSystemProperties': {}, 'payload': provenance,
    'classpath': records}, indent=2) + '\n')
with (out / 'run.log').open('w') as log:
    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=3600)
worker = (models / 'scripts/fleet/smoke-worker.sh').read_text()
gate = worker.split("<<'GATE'\n", 1)[1].split('\nGATE', 1)[0]
(out / 'gate.py').write_text(gate)
verdict = subprocess.check_output([sys.executable, str(out / 'gate.py'), str(out / 'report.json')], text=True)
(out / 'verdict.txt').write_text(verdict)
print(verdict, end='')
if verdict.strip() != 'PASS':
    raise RuntimeError('Released-runtime default smoke failed')
