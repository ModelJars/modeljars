"""Release provenance checks without network access or model inference."""
import argparse
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('run-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def test_stale_or_different_demo_dependencies_rejected(self):
        marker = 'org.modeljars.huggingface:llava.q4_k_s:1.0.0-q4_k_s.1'
        current = '//DEPS org.modeljars:modeljars:0.1.77\n//DEPS ' + marker + '\n'
        release.verify_demo_dependencies(current, '0.1.77', marker)
        for source in [current.replace('0.1.77', '0.1.65'), current.replace('llava.q4_k_s', 'llava.q4_k_m'),
                       current + '//DEPS unrelated:runtime:1.0.0\n', current + '//DEPS ' + marker + '\n']:
            with self.assertRaisesRegex(ValueError, 'exact released SDK'):
                release.verify_demo_dependencies(source, '0.1.77', marker)

    def test_unreleased_versions_rejected(self):
        for value in ['0.1.2-SNAPSHOT', 'latest', '1.2.3;exit', '../1.2.3']:
            with self.assertRaises(argparse.ArgumentTypeError):
                release.version(value)
        self.assertEqual(release.version('0.1.77'), '0.1.77')

    def test_graph_and_loaded_bytes_bound(self):
        versions = dict(modeljarsVersion='0.1.77', modelsVersion='0.5.16', vectorsVersion='0.1.33')
        expected = [
            ('modeljars', 'org.modeljars', 'modeljars', '0.1.77'),
            ('modelsApi', 'com.integrallis', 'models-api', '0.5.16'),
            ('javaBackend', 'com.integrallis', 'backend-java', '0.5.16'),
            ('nativeBackend', 'com.integrallis', 'backend-native', '0.5.16'),
            ('vectors', 'com.integrallis', 'vectors-core', '0.1.33'),
        ]
        consumer = {'runtimeJars': [dict(id=identity, fileName=f'{artifact}-{version}.jar', sha256='a' * 64)
                                    for identity, _, artifact, version in expected]}
        graph = [dict(group=group, name=artifact, version=version, sha256='a' * 64)
                 for _, group, artifact, version in expected]
        self.assertEqual(len(release.artifacts(consumer, versions, graph)), 5)
        graph[3]['sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'nativeBackend'):
            release.artifacts(consumer, versions, graph)
        graph[3]['sha256'] = 'a' * 64
        graph[3]['version'] = '0.5.15'
        with self.assertRaisesRegex(ValueError, 'nativeBackend'):
            release.artifacts(consumer, versions, graph)
        graph[3]['version'] = '0.5.16'
        consumer['runtimeJars'].append(consumer['runtimeJars'][0])
        with self.assertRaisesRegex(ValueError, 'modeljars'):
            release.artifacts(consumer, versions, graph)


if __name__ == '__main__':
    unittest.main()
