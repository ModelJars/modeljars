#!/usr/bin/env python3
"""Verify a qualified candidate using a fresh, Central-only released SDK graph on Linux."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import urllib.request

CENTRAL = 'https://repo.maven.apache.org/maven2'


def read(path):
    return json.loads(path.read_text())


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def version(value):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value):
        raise argparse.ArgumentTypeError('An exact released major.minor.patch version is required')
    return value


def execute(command, directory, name, env=None):
    with (directory / (name + '.stdout.log')).open('w') as stdout, \
            (directory / (name + '.stderr.log')).open('w') as stderr:
        subprocess.run(list(map(str, command)), stdout=stdout, stderr=stderr, env=env, check=True)


def artifacts(consumer, versions, resolved):
    expected = [('modeljars', 'org.modeljars', 'modeljars', versions['modeljarsVersion']),
                ('modelsApi', 'com.integrallis', 'models-api', versions['modelsVersion']),
                ('javaBackend', 'com.integrallis', 'backend-java', versions['modelsVersion']),
                ('nativeBackend', 'com.integrallis', 'backend-native', versions['modelsVersion']),
                ('vectors', 'com.integrallis', 'vectors-core', versions['vectorsVersion'])]
    result = []
    for identity, group, artifact, release in expected:
        loaded = [r for r in consumer['runtimeJars'] if r['id'] == identity]
        graph = [r for r in resolved if r['group'] == group and r['name'] == artifact]
        filename = f'{artifact}-{release}.jar'
        if len(loaded) != 1 or len(graph) != 1 or graph[0]['version'] != release \
                or loaded[0]['fileName'] != filename or loaded[0]['sha256'] != graph[0]['sha256']:
            raise ValueError('Loaded class does not match the resolved release: ' + identity)
        result.append(dict(id=identity, sha256=loaded[0]['sha256'],
                           url=f'{CENTRAL}/{group.replace(".", "/")}/{artifact}/{release}/{filename}'))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['campaign', 'payload', 'output', 'gradle-wrapper', 'java']:
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ['modeljars-version', 'models-version', 'vectors-version']:
        parser.add_argument('--' + name, type=version, required=True)
    args = parser.parse_args()
    if platform.system() != 'Linux':
        parser.error('Run model inference on the remote Linux worker')
    campaign, payload, output = args.campaign.resolve(), args.payload.resolve(), args.output.resolve()
    report = read(campaign / 'results/admission-attempt.json')
    candidates = [c for c in report['candidates'] if c['backendQualified']]
    if not candidates:
        raise ValueError('No passing backend candidate; do not manufacture a release consumer pass')
    output.mkdir(exist_ok=False, parents=True)
    project = output / 'resolver'
    project.mkdir()
    (project / 'settings.gradle').write_text("rootProject.name = 'released-vision-consumer'\n")
    (project / 'build.gradle').write_text('''plugins { id 'java' }
repositories { maven { url = uri('https://repo.maven.apache.org/maven2') } }
dependencies { implementation 'org.modeljars:modeljars:VERSION' }
tasks.register('stage') { doLast {
    def jars = configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts
    assert jars.collect { it.file.name }.toSet().size() == jars.size(): 'Duplicate artifact file names'
    def rows = jars.collect { a ->
        def id = a.moduleVersion.id
        [group:id.group, name:id.name, version:id.version, file:a.file.name]
    }
    file('resolved.json').text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(rows))
    copy { from jars.collect { it.file }; into 'lib' }
} }
'''.replace('VERSION', args.modeljars_version))
    env = dict(os.environ)
    env['JAVA_HOME'] = str(args.java.resolve().parent.parent)
    # A fresh user-home also excludes global init scripts, cached substitutions and mavenLocal.
    execute([args.gradle_wrapper.resolve(), '--no-daemon', '--console=plain', '--gradle-user-home',
             output / 'gradle-cache', '-p', project, 'stage'], output, 'resolve', env)
    resolved = read(project / 'resolved.json')
    for row in resolved:
        row['sha256'] = digest(project / 'lib' / row['file'])
    (output / 'resolved.json').write_text(json.dumps(resolved, indent=2) + '\n')
    cases = read(campaign / 'cases.json')['cases']
    selected = [next(c for c in cases if c['dataset'] == name and not c['blank'])
                for name in ['vqav2', 'textvqa']]
    selected.append(selected[0])
    versions = dict(modeljarsVersion=args.modeljars_version, modelsVersion=args.models_version,
                    vectorsVersion=args.vectors_version)
    for candidate in candidates:
        quant = candidate['variant']
        directory = output / quant
        directory.mkdir()
        marker_name = f'second-state.llava-v1.5-7b-gguf.{quant.lower()}'
        marker_version = f'1.0.0-{quant.lower()}.1'
        marker = payload / 'measured-markers' / f'{marker_name}-{marker_version}.jar'
        if not marker.is_file():
            raise ValueError('Missing explicitly staged candidate marker: ' + str(marker))
        rows = []
        for case in selected:
            runs = read(campaign / 'results' / quant / 'candidate' / case['id'] / 'runs.json')
            answer = next(r['answer'] for r in runs if r['run'] == 0)
            encoded = [base64.b64encode(s.encode()).decode() for s in [case['question'], answer]]
            rows.append('\t'.join([case['id'], str(campaign / case['image']), *encoded]))
        tsv = directory / 'cases.tsv'
        tsv.write_text('\n'.join(rows) + '\n')
        coordinate = f'org.modeljars.huggingface:{marker_name}:{marker_version}'
        cp = ':'.join(map(str, sorted((project / 'lib').glob('*.jar')) + [marker]))
        command = [args.java, '--add-modules=jdk.incubator.vector', '--enable-native-access=ALL-UNNAMED',
                   '-Xmx12g', '-Dmodeljars.cache=' + str(directory / 'cache'), '-cp', cp]
        execute(command + [payload / 'PreviewVision.java', coordinate, tsv, directory / 'consumer.json'],
                directory, 'consumer')
        consumer = read(directory / 'consumer.json')
        if not consumer['passed'] or consumer['modelsEvidenceVersion'] != args.models_version \
                or set(consumer['runtimeProperties']) != {'modeljars.cache'}:
            raise ValueError('Released consumer failed or used nondefault execution settings')
        published = artifacts(consumer, versions, resolved)
        # Verify independently of the resolver and the loaded code-source paths.
        for artifact in published:
            with urllib.request.urlopen(artifact['url'], timeout=120) as response:
                if hashlib.sha256(response.read()).hexdigest() != artifact['sha256']:
                    raise ValueError('Central bytes differ from the loaded runtime')
        demo = payload / ('pr380-llava-q4k' + quant[-1].lower() + '-demo.java')
        execute(command + [demo, campaign / selected[0]['image'], selected[0]['question']], directory, 'demo')
        if 'Answer: ' + consumer['cases'][0]['answer'] + '\n' not in (directory / 'demo.stdout.log').read_text():
            raise ValueError('Generated demo changed the measured answer')
        receipt = dict(schema='modeljars-vision-release-v1', **versions, artifacts=published,
                       consumer=consumer, passed=True, markerPublished=False,
                       stagedMarkerSha256=digest(marker), harnessSha256=digest(Path(__file__)),
                       backendReportSha256=digest(campaign / 'results/admission-attempt.json'),
                       demoSha256=digest(demo))
        (directory / 'release.json').write_text(json.dumps(receipt, indent=2) + '\n')
        print(quant + ': released runtime and generated source PASS; marker Central resolution still required', flush=True)


if __name__ == '__main__':
    main()
