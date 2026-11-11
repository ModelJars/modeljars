"""Reject incomplete or mismatched source previews before executing inference."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('preview', Path(__file__).with_name('run-preview.py'))
PREVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREVIEW)


class PreviewEvidenceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        results = self.root / 'campaign/results'
        results.mkdir(parents=True)
        self.native = self.root / 'candidate.so'
        self.native.write_bytes(b'synthetic kernel bytes; never executed')
        self.report = {'receipt': {'nativeSha256': hashlib.sha256(self.native.read_bytes()).hexdigest()},
                       'candidates': [{'variant': 'Q4_K_S', 'backendQualified': False}]}
        (results / 'admission-attempt.json').write_text(json.dumps(self.report))
        self.markers = [self.root / 'COLLECTION_COMPLETE', results / 'COLLECTION_COMPLETE']
        for marker in self.markers:
            marker.touch()

    def test_completed_attempt_preserves_a_rejected_admission(self):
        campaign, report = PREVIEW.completed_attempt(self.root, self.native)
        self.assertEqual(campaign, self.root / 'campaign')
        self.assertEqual(report, self.report)
        self.assertFalse(report['candidates'][0]['backendQualified'])

    def test_missing_collection_or_evaluation_marker_is_rejected(self):
        for marker in self.markers:
            marker.unlink()
            with self.assertRaisesRegex(ValueError, 'Finish both-arm'):
                PREVIEW.completed_attempt(self.root, self.native)
            marker.touch()

    def test_a_different_native_binary_is_rejected(self):
        self.native.write_bytes(b'unmeasured kernel')
        with self.assertRaisesRegex(ValueError, 'differs from the measured'):
            PREVIEW.completed_attempt(self.root, self.native)


if __name__ == '__main__':
    unittest.main()
