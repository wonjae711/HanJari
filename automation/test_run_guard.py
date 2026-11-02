import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import controller as c
import final_verify as f
import run_guard as g

def answer(outcome):
    return {'outcome': outcome, 'summary': 'test', 'evidence': ['fixture only'], 'review_files': []}

class RunGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.store = c.Store(self.root / 'state')
        state = c.new_state({'title': 'test', 'acceptance_criteria': ['test']}, 'mock')
        state.update(stage='implement', active=True)
        c.save(self.store.path, state)

    def activate(self):
        self.store.update(lambda s: s.update(active=True))

    def test_third_failure_latches_across_resumes_and_blocks_fourth(self):
        calls = []
        def fail(*args):
            calls.append(1)
            raise OSError('permission denied')
        for _ in range(3):
            self.activate()
            with self.assertRaises(OSError): c.run(self.store, {}, fail)
        self.assertTrue(c.read(self.store.path)['run_guard']['latched'])
        self.store = c.Store(self.store.directory)  # simulate process restart
        self.activate()
        c.run(self.store, {}, fail)
        self.assertEqual(len(calls), 3)
        self.assertEqual(c.read(self.store.path)['repairs'], 0)

    def test_blocked_results_also_count(self):
        for _ in range(3):
            self.activate()
            c.run(self.store, {}, lambda *a: answer('blocked'))
        self.assertTrue(c.read(self.store.path)['run_guard']['latched'])

    def test_global_limit_checked_before_next_call(self):
        calls = []
        def ok(*args):
            calls.append(1)
            return answer('pass')
        c.run(self.store, {'run_limits': {'max_attempts': 1}}, ok)
        s = c.read(self.store.path)
        self.assertEqual(s['stage'], 'review')
        self.assertTrue(s['run_guard']['latched'])
        self.assertEqual(len(calls), 1)

    def test_stage_limit_persists_with_manual_resumes(self):
        cfg = {'run_limits': {'max_stage_attempts': 2, 'max_consecutive_failures': 5}}
        calls = []
        def blocked(*args):
            calls.append(1)
            return answer('blocked')
        for _ in range(3):
            self.activate()
            c.run(self.store, cfg, blocked)
        self.assertEqual(len(calls), 2)
        self.assertTrue(c.read(self.store.path)['run_guard']['latched'])

    def test_reset_requires_reason_and_does_not_activate(self):
        s = c.read(self.store.path)
        g.trip(s, 'test stop', c.event)
        with self.assertRaises(ValueError): g.reset(s, ' ', c.event)
        g.reset(s, 'Fixed configuration; approved retry', c.event)
        self.assertFalse(s['active'])
        self.assertFalse(s['run_guard']['latched'])
        self.assertEqual(len(s['run_guard_history']), 1)

    def test_duplicate_project_runner_other_state_is_refused(self):
        repo = self.root / 'repo'
        with c.lock(repo / 'automation/.runtime-locks/runner.lock'):
            with self.assertRaises(RuntimeError):
                c.run(self.store, {'project_dir': str(repo)}, lambda *a: self.fail('must not invoke'))

    def test_complete_state_never_invokes(self):
        self.store.update(lambda s: s.update(stage='pr_ready', active=False))
        c.run(self.store, {}, lambda *a: self.fail('must not invoke'))
        self.assertIsNone(c.read(self.store.path)['running'])

    def test_success_clears_failure_streak_without_clearing_totals(self):
        s = c.read(self.store.path)
        g.claim(s, {}, c.event)
        g.record(s, dict(s), 'blocked', {}, c.event)
        g.record(s, dict(s), 'pass', {}, c.event)
        self.assertEqual(s['run_guard']['consecutive_failures']['1:implement'], 0)
        self.assertEqual(s['run_guard']['attempts'], 1)

    def test_invalid_limits_are_rejected(self):
        for value in (0, -1, True, '3'):
            with self.assertRaises(ValueError): g.limits({'run_limits': {'max_attempts': value}})

    def test_recheck_preserves_original_and_never_resets_existing_recheck(self):
        original = c.Store(self.root / '.runtime-pilot')
        s = c.new_state({'title': 'pilot', 'acceptance_criteria': ['test']}, 'live')
        s['stage'] = 'pr_ready'
        c.save(original.path, s)
        before = original.path.read_bytes()
        with patch.object(c, 'ROOT', self.root):
            replay = f.select_store(True)
            self.assertEqual(c.read(replay.path)['stage'], 'final_test')
            replay.update(lambda x: x.update(stage='pr_ready'))
            again = f.select_store(True)
            self.assertEqual(c.read(again.path)['stage'], 'pr_ready')
        self.assertEqual(original.path.read_bytes(), before)

if __name__ == '__main__': unittest.main()
