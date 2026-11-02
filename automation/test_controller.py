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


    # -- 권한 거부 분류 + 테스트 증거 검증 (controller.evaluate_claude_envelope) --

    def _surefire_report(self, repo, tests=3, failures=0, errors=0, name="TEST-Example.xml"):
        reports = Path(repo) / "target" / "surefire-reports"
        reports.mkdir(parents=True, exist_ok=True)
        path = reports / name
        path.write_text(
            f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}"></testsuite>',
            encoding="utf-8",
        )
        return path

    def _all_reports(self, repo):
        from verification_evidence import EXPECTED
        for folder, suites in EXPECTED.items():
            for name, count in suites.items():
                path = Path(repo) / 'target' / folder / ('TEST-' + name + '.xml')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f'<testsuite name="{name}" tests="{count}" failures="0" errors="0" skipped="0"/>')

    def test_auxiliary_denial_with_fresh_passing_report_still_passes(self):
        # 핵심 테스트 명령은 성공했고, 거부된 건 참고용 보조 명령(cat 등)뿐인 경우:
        # 결과는 그대로 통과해야 하고, 거부 내역은 별도 경고 파일로 남아야 한다.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        started_at = time.time()
        time.sleep(0.02)
        self._all_reports(repo)  # 필수 전체 클래스의 최신 성공 결과

        attempt = Path(self.tmp.name) / "attempt"
        envelope = {
            "is_error": False,
            "permission_denials": [
                {"tool_name": "Bash", "tool_input": {"command": "cat pom.xml README.md"}},
            ],
            "structured_output": result("pass"),
        }
        out = c.evaluate_claude_envelope(envelope, "claude", repo, started_at, attempt)
        self.assertEqual(out["outcome"], "pass")
        self.assertTrue((attempt / "denials.json").exists())

    def test_essential_denial_recovered_by_later_retry_still_passes(self):
        # 실제로 겪은 상황: 파이프(| Select-String) 붙은 mvnw 호출은 거부됐지만,
        # Claude가 같은 세션에서 일반 명령으로 재시도해 실제로 통과했다.
        # 거부 기록만 보고 막지 말고, 새로 생성된 리포트 증거를 우선해야 한다.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        started_at = time.time()
        time.sleep(0.02)
        self._all_reports(repo)

        attempt = Path(self.tmp.name) / "attempt"
        envelope = {
            "is_error": False,
            "permission_denials": [
                {"tool_name": "PowerShell", "tool_input": {
                    "command": ".\\mvnw.cmd -B verify 2>&1 | Select-String -Pattern 'Tests run:' | Select-Object -Last 25"
                }},
            ],
            "structured_output": result("pass"),
        }
        out = c.evaluate_claude_envelope(envelope, "claude", repo, started_at, attempt)
        self.assertEqual(out["outcome"], "pass")
        self.assertTrue((attempt / "denials.json").exists())

    def test_essential_denial_blocks_even_if_agent_claims_pass(self):
        # mvnw 명령 자체가 거부되어 테스트가 실행되지 못한 경우: 에이전트가 pass라고
        # 주장해도 신뢰하지 않고 즉시 실패해야 한다.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        envelope = {
            "is_error": False,
            "permission_denials": [
                {"tool_name": "PowerShell", "tool_input": {"command": ".\\mvnw.cmd -B verify"}},
            ],
            "structured_output": result("pass"),
        }
        with self.assertRaises(RuntimeError):
            c.evaluate_claude_envelope(envelope, "claude", repo, time.time(), Path(self.tmp.name) / "attempt")

    def test_pass_without_fresh_test_evidence_is_rejected(self):
        # 권한 거부는 전혀 없지만, 테스트가 실제로 돌았다는 새 리포트 증거가 없는데
        # outcome=pass만 돌아온 경우(예: 에이전트의 잘못된 자가 보고, 또는 이전 실행의
        # 낡은 리포트만 존재): 자가 보고만으로 통과시키지 않는다.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        self._surefire_report(repo)  # 낡은(stale) 리포트: 아래 since보다 먼저 생성됨
        time.sleep(0.02)
        since = time.time()  # 리포트보다 나중 시각 -> 리포트가 "새로 생성됨"으로 인정되지 않음

        envelope = {
            "is_error": False,
            "permission_denials": [],
            "structured_output": result("pass"),
        }
        with self.assertRaises(RuntimeError):
            c.evaluate_claude_envelope(envelope, "claude", repo, since, Path(self.tmp.name) / "attempt")

    def test_pass_with_fresh_but_failing_report_is_rejected(self):
        # 새로 생성된 리포트가 있어도 그 안에 실패/오류가 있으면 pass로 인정하지 않는다.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        started_at = time.time()
        time.sleep(0.02)
        self._surefire_report(repo, tests=5, failures=1, errors=0)

        envelope = {
            "is_error": False,
            "permission_denials": [],
            "structured_output": result("pass"),
        }
        with self.assertRaises(RuntimeError):
            c.evaluate_claude_envelope(envelope, "claude", repo, started_at, Path(self.tmp.name) / "attempt")

    def test_non_claude_agent_skips_report_evidence_check(self):
        # codex(review 등) 결과는 mvnw 테스트 리포트 증거 검사를 적용하지 않는다 —
        # 해당 단계는 애초에 테스트를 직접 실행하지 않기 때문.
        repo = Path(self.tmp.name) / "repo"
        repo.mkdir()
        envelope = {
            "is_error": False,
            "permission_denials": [],
            "structured_output": result("pass"),
        }
        out = c.evaluate_claude_envelope(envelope, "codex", repo, time.time(), Path(self.tmp.name) / "attempt")
        self.assertEqual(out["outcome"], "pass")

if __name__ == "__main__":
    unittest.main()
