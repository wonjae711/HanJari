import os
from pathlib import Path
import tempfile
import unittest
import final_verify as f

class FinalEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        for folder, suites in f.EXPECTED.items():
            for name, count in suites.items():
                p = self.repo / 'target' / folder / ('TEST-' + name + '.xml')
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(f'<testsuite name="com.hanjari.movie.{name}" tests="{count}" failures="0" errors="0" skipped="0"/>')
    def test_all_required_suites(self):
        self.assertEqual(sum(s['tests'] for s in f.check_reports(self.repo, 0)), 35)
    def test_missing_or_stale_integration_is_rejected(self):
        p = next((self.repo / 'target/failsafe-reports').glob('*.xml'))
        os.utime(p, (1, 1))
        with self.assertRaises(ValueError): f.check_reports(self.repo, 2)
        p.unlink()
        with self.assertRaises(ValueError): f.check_reports(self.repo, 0)
    def test_failures_skips_and_empty_suites_are_rejected(self):
        p = next((self.repo / 'target/failsafe-reports').glob('*.xml'))
        original = p.read_text()
        for field in ('failures', 'errors', 'skipped'):
            p.write_text(original.replace(f'{field}="0"', f'{field}="1"'))
            with self.assertRaises(ValueError): f.check_reports(self.repo, 0)
        p.write_text(original.replace('tests="4"', 'tests="0"'))
        with self.assertRaises(ValueError): f.check_reports(self.repo, 0)
    def test_manifest_detects_source_change(self):
        p = self.repo / 'pom.xml'
        p.write_text('before')
        before = f.source_manifest(self.repo)
        p.write_text('after')
        self.assertNotEqual(before, f.source_manifest(self.repo))

if __name__ == '__main__': unittest.main()
