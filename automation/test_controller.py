import tempfile
import threading
import time
import unittest
from pathlib import Path
import controller as c

def result(outcome="pass"):
    return {"outcome": outcome, "summary": "test", "evidence": ["test evidence"], "review_files": ["src/example:1"]}

class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = c.Store(self.tmp.name)
        self.state = c.new_state({"title": "test", "acceptance_criteria": ["works"]}, "mock")
        c.save(self.store.path, self.state)

    def test_usage_thresholds_and_invalid_values(self):
        path = Path(self.tmp.name) / "usage.json"
        for five, seven, allowed in [(79.9, 69.9, True), (80, 12, False), (1, 70, False), (True, 1, False), (-1, 1, False), (float("nan"), 1, False)]:
            c.save(path, {"five_hour": {"used_percentage": five}, "seven_day": {"used_percentage": seven}})
            if allowed:
                c.usage_gate(path)
            else:
                with self.assertRaises(ValueError): c.usage_gate(path)
        c.save(path, {"five_hour": {"used_percentage": 1}, "seven_day": {"used_percentage": 1}})
        with self.assertRaises(ValueError): c.usage_gate(path, timestamp=time.time()+301)

    def test_manual_activation_and_plan_gate(self):
        calls = []
        def invoke(config, s, directory):
            calls.append(s["stage"])
            return result()
        c.run(self.store, {}, invoke)
        self.assertEqual(calls, [])
        self.store.update(lambda s: s.update(active=True))
        c.run(self.store, {}, invoke)
        self.assertEqual(calls, ["plan", "spec_review"])
        s = c.read(self.store.path)
        self.assertEqual(s["stage"], "awaiting_plan_approval")
        self.assertFalse(s["active"])

    def test_three_repairs_then_one_manager_replan(self):
        self.store.update(lambda s: s.update(active=True, stage="implement"))
        calls = []
        def invoke(config, s, directory):
            calls.append(s["stage"])
            return result("changes" if s["stage"] == "review" else "pass")
        c.run(self.store, {}, invoke)
        s = c.read(self.store.path)
        self.assertEqual(calls.count("implement"), 5)  # initial + 3 repairs + 1 replan
        self.assertEqual(calls.count("manager"), 1)
        self.assertEqual(s["stage"], "needs_user")
        self.assertFalse(s["active"])

    def test_pause_during_work_preserves_result_without_next_step(self):
        self.store.update(lambda s: s.update(active=True))
        def invoke(config, s, directory):
            self.store.update(lambda live: live.update(active=False))
            return result()
        c.run(self.store, {}, invoke)
        s = c.read(self.store.path)
        self.assertEqual(s["stage"], "spec_review")
        self.assertEqual(len(s["results"]), 1)
        self.assertFalse(s["active"])

    def test_old_revision_result_rejected(self):
        self.store.update(lambda s: s.update(active=True))
        def invoke(config, s, directory):
            self.store.update(lambda live: live.update(revision=2))
            return result()
        c.run(self.store, {}, invoke)
        s = c.read(self.store.path)
        self.assertEqual(s["results"], [])
        self.assertFalse(s["active"])

    def test_execution_failure_does_not_consume_repair(self):
        self.store.update(lambda s: s.update(active=True, stage="implement"))
        def invoke(*args): raise OSError("CLI unavailable")
        with self.assertRaises(OSError): c.run(self.store, {}, invoke)
        s = c.read(self.store.path)
        self.assertEqual(s["repairs"], 0)
        self.assertIsNone(s["running"])
        self.assertFalse(s["active"])

    def test_duplicate_runner_refused(self):
        with c.lock(self.store.directory / "runner.lock"):
            with self.assertRaises(RuntimeError): c.run(self.store, {}, lambda *a: result())

    def test_evidence_required(self):
        r = result()
        r["evidence"] = []
        with self.assertRaises(ValueError): c.validate(r)

    def test_smoke_accepts_terminal_newlines_only(self):
        from smoke_cli import verify_read
        for suffix in ("", "\n", "\r\n"):
            r = result()
            r["summary"] = "PROBE" + suffix
            verify_read(r, "PROBE")
        for text in (" PROBE", "PROBE ", "PROBE-other", "PRO\nBE"):
            r = result()
            r["summary"] = text
            with self.assertRaises(RuntimeError): verify_read(r, "PROBE")
        r = result("blocked")
        r["summary"] = "PROBE"
        with self.assertRaises(RuntimeError): verify_read(r, "PROBE")

    def test_codex_discovered_when_missing_from_path(self):
        from unittest.mock import patch
        exe = Path(self.tmp.name) / "OpenAI" / "Codex" / "bin" / "build-id" / "codex.exe"
        exe.parent.mkdir(parents=True)
        exe.touch()
        with patch.object(c.shutil, "which", return_value=None), patch.dict(c.os.environ, {"LOCALAPPDATA": self.tmp.name}):
            self.assertEqual(c.executable("codex "), str(exe.resolve()))
            with self.assertRaises(ValueError): c.executable("missing-custom-codex.exe")

    def test_explicit_executable_and_path_take_priority(self):
        from unittest.mock import patch
        exe = Path(self.tmp.name) / "custom.exe"
        exe.touch()
        with patch.object(c.shutil, "which", return_value=None):
            self.assertEqual(c.executable(str(exe)), str(exe.resolve()))
        with patch.object(c.shutil, "which", return_value="PATH-codex.exe"):
            self.assertEqual(c.executable("codex"), "PATH-codex.exe")

    def test_codex_home_is_only_set_for_child(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ, {"CODEX_HOME": "original"}):
            env = c.child_environment({"codex_home": self.tmp.name})
            self.assertEqual(env["CODEX_HOME"], str(Path(self.tmp.name).resolve()))
            self.assertEqual(os.environ["CODEX_HOME"], "original")

    def test_success_stops_at_pr_preparation(self):
        self.store.update(lambda s: s.update(active=True, stage="implement"))
        c.run(self.store, {}, lambda *a: result())
        s = c.read(self.store.path)
        self.assertEqual(s["stage"], "pr_ready")
        self.assertFalse(s["active"])

if __name__ == "__main__":
    unittest.main()
