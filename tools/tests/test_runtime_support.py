import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('runtime_support', Path(__file__).parents[1] / 'runtime-support.py')
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)


class RuntimeSupportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.jar = Path(self.directory.name) / 'backend.jar'
        self.class_name = 'com/integrallis/models/backend/purejava/lfm2/Lfm2SequenceEncoder.class'
        with zipfile.ZipFile(self.jar, 'w') as jar:
            jar.writestr(self.class_name, b'fixture')
        self.policy = {'schemaVersion': 1, 'minimumModelsVersion': '0.3.56', 'workloads': {
            'embedding-qualifications.json': {'classes': [], 'backends': ['pure-java'],
                'architectures': {'lfm2': ['backend-java:' + self.class_name[:-6].replace('/', '.')]}}}}
        self.catalog = {'models': [{'id': 'lfm2', 'architecture': 'lfm2'}]}
        self.manifests = {'embedding-qualifications.json': {'entries': [
            {'modelId': 'lfm2', 'qualified': True, 'backend': 'pure-java'}]}}
        self.artifacts = [{'group': 'com.integrallis', 'module': 'backend-java',
                           'version': '0.3.57', 'file': str(self.jar)}]

    def check(self):
        return support.verify(self.policy, self.catalog, self.manifests, self.artifacts)[0]

    def test_matching_runtime(self):
        self.assertEqual([], self.check())

    def test_old_resolved_runtime(self):
        self.artifacts[0]['version'] = '0.3.54'
        self.assertIn('resolved 0.3.54', '\n'.join(self.check()))

    def test_missing_encoder_even_with_correct_version_label(self):
        with zipfile.ZipFile(self.jar, 'w'):
            pass
        self.assertIn('Lfm2SequenceEncoder', '\n'.join(self.check()))

    def test_unreviewed_architecture(self):
        self.catalog['models'][0]['architecture'] = 'new-architecture'
        self.assertIn('no reviewed', '\n'.join(self.check()))

    def test_mixed_native_version(self):
        self.artifacts.append({**self.artifacts[0], 'module': 'backend-native', 'version': '0.3.54'})
        self.assertIn('Mixed Models runtime', '\n'.join(self.check()))

    def test_unqualified_entry_does_not_claim_support(self):
        self.manifests['embedding-qualifications.json']['entries'][0]['qualified'] = False
        self.catalog['models'][0]['architecture'] = 'unimplemented'
        self.assertEqual([], self.check())


if __name__ == '__main__':
    unittest.main()
