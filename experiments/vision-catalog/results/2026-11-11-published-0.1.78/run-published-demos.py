#!/usr/bin/env python3
"""Resolve ordinary generated JBang demos after their markers are on Central."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
for name in ('released', 'campaign', 'output', 'jbang', 'java'):
    parser.add_argument('--' + name, type=Path, required=True)
args = parser.parse_args()
assert platform.system() == 'Linux'
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
java = args.java.resolve()
jbang = args.jbang.resolve()
tool_home = jbang.parent.parent
env = dict(os.environ, JAVA_HOME=str(java.parent.parent), JBANG_DIR=str(tool_home),
           JBANG_CACHE_DIR=str(output / 'jbang-cache'),
           JBANG_REPO=str(output / 'maven-cache'))
version = subprocess.check_output([jbang, 'version'], env=env, text=True).strip()
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
cases = json.loads((args.campaign / 'cases.json').read_text())['cases']
for quant in ('Q4_K_S', 'Q4_K_M'):
    released = args.released / quant
    receipt = json.loads((released / 'release.json').read_text())
    assert receipt['passed'] and receipt['consumer']['passed']
    demo = released / ('pr380-llava-q4k' + quant[-1].lower() + '-demo.java')
    assert digest(demo) == receipt['demoSha256']
    coordinate = receipt['consumer']['marker']
    group, artifact, artifact_version = coordinate.split(':')
    jar_name = artifact + '-' + artifact_version + '.jar'
    base_url = 'https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + '/' + artifact + '/' + artifact_version + '/'
    directory = output / quant
    directory.mkdir()
    published = {}
    for suffix in ('.pom', '.jar'):
        name = artifact + '-' + artifact_version + suffix
        url = base_url + name
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        published[name] = dict(url=url, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data))
    case_result = receipt['consumer']['cases'][0]
    case = next(c for c in cases if c['id'] == case_result['id'])
    command = [str(jbang), '--verbose', 'run', '-Dmodeljars.cache=' + str(directory / 'model-cache'),
               str(demo.resolve()), str((args.campaign / case['image']).resolve()), case['question']]
    (directory / 'command.json').write_text(json.dumps(dict(command=command,
        environment={k:env[k] for k in ('JAVA_HOME','JBANG_DIR','JBANG_CACHE_DIR','JBANG_REPO')}), indent=2) + '\n')
    with (directory / 'stdout.log').open('w') as stdout, (directory / 'stderr.log').open('w') as stderr:
        result = subprocess.run(command, env=env, stdout=stdout, stderr=stderr, timeout=1800)
    assert result.returncode == 0, 'JBang demo failed: ' + quant
    assert 'Answer: ' + case_result['answer'] + '\n' in (directory / 'stdout.log').read_text()
    resolved = [p for p in output.rglob(jar_name) if p.is_file()]
    assert resolved, 'Published marker did not resolve into the fresh dependency caches'
    assert all(digest(p) == published[jar_name]['sha256'] for p in resolved)
    runtime_files = {}
    for runtime in receipt['consumer']['runtimeJars']:
        matches = [p for p in output.rglob(runtime['fileName']) if p.is_file()]
        assert matches and all(digest(p) == runtime['sha256'] for p in matches), runtime['id']
        runtime_files[runtime['id']] = dict(sha256=runtime['sha256'], files=[str(p) for p in matches])
    report = dict(schema='modeljars-vision-published-demo-v1', variant=quant, passed=True,
                  marker=coordinate, modeljarsVersion=receipt['modeljarsVersion'],
                  modelsVersion=receipt['modelsVersion'], jbangVersion=version,
                  jbangJarSha256=digest(jbang.parent / 'jbang.jar'),
                  generatedSourceSha256=digest(demo), releaseReportSha256=digest(released / 'release.json'),
                  publishedMarker=published, resolvedMarkerFiles=[str(p) for p in resolved],
                  resolvedRuntimeFiles=runtime_files,
                  caseId=case_result['id'], expectedAnswer=case_result['answer'],
                  command=command, exitCode=result.returncode)
    (directory / 'published-demo.json').write_text(json.dumps(report, indent=2) + '\n')
    print(quant + ': published marker and ordinary generated JBang demo PASS', flush=True)
