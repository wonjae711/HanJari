import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import controller as c
import final_verify as f
from verification_evidence import EXPECTED

def result():
    return dict(outcome='pass', summary='test', evidence=['test'], review_files=[])

class VerificationIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.store = c.Store(self.repo / 'state')
        s = c.new_state({'title': 'movie', 'task_id': 'HJ-001'}, 'live')
        s.update(stage='review', active=True)
        c.save(self.store.path, s)

    def reports(self):
        for folder, suites in EXPECTED.items():
            for name, count in suites.items():
                p = self.repo / 'target' / folder / ('TEST-' + name + '.xml')
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="0"/>')

    def test_default_workflow_routes_final_to_verifier(self):
        calls = []
        def agent(cfg, s, directory):
            calls.append(s['stage'])
            return result()
        with patch.object(c, 'invoke', side_effect=agent), patch.object(f, 'verify_then_assess', return_value=result()) as final:
            c.run(self.store, {})
        self.assertEqual(calls, ['review'])
        final.assert_called_once()
        s = c.read(self.store.path)
        self.assertEqual(s['stage'], 'pr_ready')
        self.assertFalse(s['active'])

    def test_verification_failure_never_reaches_pr_ready(self):
        with patch.object(c, 'invoke', return_value=result()), patch.object(f, 'verify_then_assess', side_effect=ValueError('Maven failed')):
            with self.assertRaises(ValueError): c.run(self.store, {})
        s = c.read(self.store.path)
        self.assertEqual(s['stage'], 'final_test')
        self.assertFalse(s['active'])
        self.assertEqual(s['repairs'], 0)

    def test_unknown_task_cannot_use_movie_policy(self):
        self.store.update(lambda s: s.update(stage='final_test', task={'task_id': 'HJ-002'}))
        with patch.object(c, 'invoke') as agent, patch.object(f, 'verify_then_assess') as final:
            with self.assertRaises(ValueError): c.run(self.store, {})
            agent.assert_not_called()
            final.assert_not_called()

    def test_partial_skipped_and_malformed_reports_rejected(self):
        self.reports()
        self.assertTrue(c.fresh_test_evidence(self.repo, 0))
        p = self.repo / 'target/failsafe-reports/TEST-MovieApiIT.xml'
        original = p.read_text()
        for contents in (original.replace('skipped="0"', 'skipped="1"'), '<bad', original.replace(' tests="4"', '')):
            p.write_text(contents)
            self.assertFalse(c.fresh_test_evidence(self.repo, 0))
        p.unlink()
        self.assertFalse(c.fresh_test_evidence(self.repo, 0))

    def test_mock_final_never_executes_maven(self):
        self.store.update(lambda s: s.update(mode='mock', stage='final_test'))
        with patch.object(f, 'verify_then_assess') as final:
            c.run(self.store, {})
            final.assert_not_called()

if __name__ == '__main__': unittest.main()
