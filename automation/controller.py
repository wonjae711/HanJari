"""Local, explicitly activated agent workflow. Python 3.11+, no dependencies."""
from __future__ import annotations
import argparse
from contextlib import contextmanager, ExitStack
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import run_guard

ROOT = Path(__file__).resolve().parent
SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "outcome": {"type": "string", "enum": ["pass", "changes", "blocked"]},
        "summary": {"type": "string"},
        "evidence": {"type": "array", "items": {"type": "string"}},
        "review_files": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["outcome", "summary", "evidence", "review_files"],
}
STEPS = {"plan", "spec_review", "implement", "review", "final_test", "manager"}

def now():
    return datetime.now(timezone.utc).isoformat()

def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))

def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temp, path)

@contextmanager
def lock(path, timeout=5):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + timeout
    while True:
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode())
            os.close(fd)
            break
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise RuntimeError(f"다른 실행 또는 복구가 필요한 잠금: {path}")
            time.sleep(.05)
    try:
        yield
    finally:
        path.unlink(missing_ok=True)

class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.path = self.directory / "state.json"

    def update(self, fn):
        with lock(self.directory / "state.lock"):
            state = read(self.path)
            fn(state)
            state["updated_at"] = now()
            save(self.path, state)
            return state

def event(s, kind, message):
    s["events"].append({"at": now(), "kind": kind, "message": message})

def new_state(task, mode):
    return {"id": uuid.uuid4().hex, "title": task["title"], "task": task,
            "mode": mode, "revision": 1, "stage": "plan", "active": False,
            "running": None, "repairs": 0, "manager_used": False,
            "events": [], "results": [], "created_at": now()}

def usage_gate(path, max_age=300, timestamp=None):
    path = Path(path)
    age = (time.time() if timestamp is None else timestamp) - path.stat().st_mtime
    if age < -30 or age > max_age:
        raise ValueError("사용량 파일이 최신 상태가 아닙니다. Claude Code에서 갱신하세요.")
    data = read(path)
    for key, limit in (("five_hour", 80), ("seven_day", 70)):
        value = data[key]["used_percentage"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 100:
            raise ValueError(f"잘못된 사용률: {key}")
        if value >= limit:
            raise ValueError(f"사용량 기준 도달: {key} {value}% >= {limit}%")
    return data

def validate(result):
    if not isinstance(result, dict) or set(result) != set(SCHEMA["required"]):
        raise ValueError("에이전트 응답 형식 불일치")
    if result["outcome"] not in ("pass", "changes", "blocked") or not isinstance(result["summary"], str):
        raise ValueError("잘못된 에이전트 판정")
    for key in ("evidence", "review_files"):
        if not isinstance(result[key], list) or any(not isinstance(x, str) for x in result[key]):
            raise ValueError(f"잘못된 필드: {key}")
    if result["outcome"] == "pass" and not result["evidence"]:
        raise ValueError("근거 없는 통과 판정은 허용하지 않습니다.")
    return result

def transition(s, result):
    stage, outcome = s["stage"], result["outcome"]
    if outcome == "blocked":
        s["active"] = False
        event(s, "attention", result["summary"])
        return
    if stage in ("implement", "review", "final_test") and outcome == "changes":
        if s["manager_used"]:
            s["stage"], s["active"] = "needs_user", False
        elif s["repairs"] < 3:
            s["repairs"] += 1
            s["stage"] = "implement"
        else:
            s["stage"] = "manager"
        event(s, "repair", f"수정 {s['repairs']}/3, 다음 단계 {s['stage']}")
        return
    if outcome == "changes":
        s["active"] = False
        event(s, "attention", "기획/재계획에 사용자 확인이 필요합니다.")
        return
    if stage == "manager":
        s["manager_used"] = True
        s["stage"] = "implement"
    else:
        s["stage"] = {"plan": "spec_review", "spec_review": "awaiting_plan_approval",
                      "implement": "review", "review": "final_test", "final_test": "pr_ready"}[stage]
    if s["stage"] in ("awaiting_plan_approval", "pr_ready"):
        s["active"] = False
        event(s, "attention", s["stage"])

def executable(value):
    value = value.strip()
    found = shutil.which(value)
    if found:
        return found
    if Path(value).is_file():
        return str(Path(value).resolve())
    # The desktop app supplies its own PATH; ordinary PowerShell may not have it.
    # Discover only the known native executable under the current user's app.
    if value.lower() in ("codex", "codex.exe"):
        local = os.environ.get("LOCALAPPDATA")
        if not local and os.environ.get("USERPROFILE"):
            local = str(Path(os.environ["USERPROFILE"]) / "AppData" / "Local")
        if local:
            root = Path(local) / "OpenAI" / "Codex" / "bin"
            try:
                candidates = [p for p in root.glob("*/codex.exe") if p.is_file()]
                direct = root / "codex.exe"
                if direct.is_file():
                    candidates.append(direct)
                if candidates:
                    return str(max(candidates, key=lambda p: (p.stat().st_mtime_ns, str(p))).resolve())
            except OSError as exc:
                raise ValueError(f"Codex 설치 폴더 접근 실패: {root}: {exc}") from exc
    raise ValueError(f"실행 파일을 찾지 못했습니다: {value}")

def child_environment(config):
    env = os.environ.copy()
    # Point to the existing subscription configuration; never create/copy auth files.
    if config.get("codex_home"):
        env["CODEX_HOME"] = str(Path(config["codex_home"]).resolve())
    return env

def prompt_for(s):
    instructions = {
        "plan": "기능 하나의 요구사항, 제외 범위, 검증 가능한 완료 기준과 구현 계획을 작성하라. 코드를 수정하지 마라.",
        "spec_review": "기획의 모호함과 누락을 독립적으로 검토하라. 현재 범위의 검증 사례와 후속 기능 제안을 구분하라. 코드를 수정하지 마라.",
        "implement": "승인된 기능을 구현하고 단위/통합 테스트를 실행하라. 수정 요청이 있으면 일괄 반영하라. 테스트 명령과 결과를 근거에 남겨라. MySQL 통합 테스트용 환경변수(HANJARI_TEST_DB_URL/USERNAME/PASSWORD)는 이 실행을 시작한 사용자 PowerShell 세션에 이미 설정되어 있다. 이 변수들의 존재 여부를 별도로 확인하려 하지 말고, `.\\mvnw.cmd -B verify`를 바로 실행하라. verify 결과(failsafe 리포트)가 그 변수들이 실제로 설정되어 있었는지 알려주는 증거다.",
        "review": "실제 변경 코드를 독립적으로 리뷰하라. 결함은 changes, 진행 불가능은 blocked로 보고하라. 파일과 행, 원인, 수정 요구를 명시하라. 코드를 수정하지 마라.",
        "final_test": "요구사항별 실제 기능 검증을 수행하라. 실행 환경이 없으면 blocked를 반환하라. 부하 테스트는 실행하지 마라. 코드는 수정하지 마라.",
        "manager": "수정 3회 후 실패 원인을 증거로 분석하고 재계획하라. 완료 기준을 낮추지 마라. 사용자 결정이 필요하면 blocked를 반환하라. 코드를 수정하지 마라.",
    }
    context = {k: s[k] for k in ("id", "task", "revision", "repairs", "results")}
    attempt_number = s.get("run_guard", {}).get("attempts", 0)
    label = f"[{s['task'].get('task_id', s['id'])} / {s['stage']} / attempt {attempt_number}]"
    return (label + "\n" + instructions[s["stage"]] + "\n"
            "새 서브에이전트는 생성하지 마라. Git commit/push/merge, PR 생성, 배포는 하지 마라. "
            "하네스 설정과 상태 파일은 수정하지 마라. 요구사항 변경은 사용자에게 요청하라. "
            "JSON outcome/summary/evidence/review_files로 결과를 반환하라. "
            "evidence에는 수행한 검증과 관찰 결과를 기록하고, review_files에는 관련 파일과 읽는 순서·핵심 포인트를 기록하라.\n"
            + json.dumps(context, ensure_ascii=False))


# -- 권한 거부 분류 및 테스트 증거 검증 --
# "필수" 명령: 이게 거부되면 테스트가 전혀 실행되지 않은 것이므로 결과를 신뢰할 수 없다.
ESSENTIAL_COMMAND_MARKERS = ("mvnw.cmd", "mvnw ")
# 이 에이전트(claude, 현재는 implement 단계에서만 사용)가 "pass"를 보고하면,
# 자가 보고만 믿지 않고 실제로 새로 생성된 테스트 리포트를 디스크에서 확인한다.
EVIDENCE_REQUIRED_AGENTS = ("claude",)

def classify_denials(denials):
    """권한 거부 목록을 (필수 명령 거부, 보조 명령 거부)로 나눈다."""
    essential, auxiliary = [], []
    for d in denials or []:
        cmd = (d.get("tool_input") or {}).get("command", "")
        bucket = essential if any(marker in cmd for marker in ESSENTIAL_COMMAND_MARKERS) else auxiliary
        bucket.append(d)
    return essential, auxiliary

def fresh_test_evidence(repo, since):
    """target/surefire-reports, target/failsafe-reports 아래 XML 리포트 중
    since 이후에 새로 생성되고 실패/오류 0건인 테스트 결과가 하나라도 있으면 True.
    기존(이전 실행) 리포트만 있거나 전혀 없으면 False — 자가 보고 pass를 믿지 않는다."""
    repo = Path(repo)
    found_passing = False
    for pattern in ("target/surefire-reports/TEST-*.xml", "target/failsafe-reports/TEST-*.xml"):
        for report in repo.glob(pattern):
            try:
                if report.stat().st_mtime < since:
                    continue
                root = ET.parse(report).getroot()
                tests = int(root.get("tests", 0))
                failures = int(root.get("failures", 0))
                errors = int(root.get("errors", 0))
            except (OSError, ET.ParseError, TypeError, ValueError):
                continue
            if tests <= 0:
                continue
            if failures or errors:
                return False  # 새로 생성된 리포트에 실패가 있음 — pass로 간주하지 않는다
            found_passing = True
    return found_passing

def evaluate_claude_envelope(envelope, agent, repo, started_at, attempt_dir):
    """Claude 실행 결과를 검증한다. 거부 기록보다 디스크의 실제 증거를 우선한다 —
    명령이 한 번 거부돼도 같은 세션에서 재시도로 성공했을 수 있기 때문이다.
    - is_error: 즉시 실패.
    - structured_output 누락: 즉시 실패.
    - outcome == pass 인 claude 에이전트 응답: 디스크의 새 테스트 리포트 증거로 먼저 확인한다.
      증거가 있으면 거부 기록과 무관하게 통과시키고, 거부는 경고로만 남긴다.
      증거가 없으면 실패 처리하며, 필수 명령(mvnw) 거부가 있었다면 그 사실을 이유에 포함한다.
    - outcome != pass: 증거 검사 없이 그대로 반환(에이전트 자신의 판정을 신뢰).
      이때도 거부 기록은 경고로 보존한다."""
    if envelope.get("is_error"):
        raise RuntimeError("Claude 실행 오류: 로그 확인 필요")
    essential, auxiliary = classify_denials(envelope.get("permission_denials"))
    denials = essential + auxiliary
    if denials:
        save(attempt_dir / "denials.json", denials)
    structured = envelope.get("structured_output")
    if structured is None:
        raise RuntimeError("Claude 응답에 structured_output이 없습니다: 로그 확인 필요")
    if agent in EVIDENCE_REQUIRED_AGENTS and structured.get("outcome") == "pass":
        if fresh_test_evidence(repo, started_at):
            if denials:
                print(f"[경고] 실행 중 명령 거부 {len(denials)}건이 있었지만, 이후 재시도로 실제 테스트 리포트 증거를 확인했습니다: {attempt_dir / 'denials.json'}", flush=True)
        elif essential:
            commands = "; ".join((d.get("tool_input") or {}).get("command", "") for d in essential)
            raise RuntimeError(
                f"필수 테스트 명령이 권한 거부로 실행되지 못했고, 새로 생성된 테스트 리포트 증거도 없습니다: {commands}"
            )
        else:
            raise RuntimeError(
                "Claude가 outcome=pass를 보고했지만 이번 실행에서 새로 생성된 테스트 리포트 증거를 "
                "target/surefire-reports 또는 target/failsafe-reports에서 찾지 못했습니다. "
                "테스트가 실제로 실행되지 않았을 수 있어 자가 보고만으로는 다음 단계로 넘기지 않습니다."
            )
    elif denials:
        print(f"[경고] 명령 거부 {len(denials)}건 (이번 판정에는 영향 없음): {attempt_dir / 'denials.json'}", flush=True)
    return structured

def invoke(config, s, directory):
    if s["mode"] == "mock":
        time.sleep(.05)
        return {"outcome": "pass", "summary": "MOCK: " + s["stage"],
                "evidence": ["모의 결과입니다. 실제 코드/테스트 검증이 아닙니다."], "review_files": []}
    repo = Path(config["project_dir"]).resolve()
    subprocess.run(["git", "-C", str(repo), "rev-parse", "--show-toplevel"], check=True, capture_output=True)
    branch = subprocess.check_output(["git", "-C", str(repo), "branch", "--show-current"], text=True).strip()
    if not branch or branch in ("main", "master", config.get("base_branch", "main")):
        raise ValueError("실제 실행에는 별도의 기능 브랜치가 필요합니다.")
    agent = "claude" if s["stage"] == "implement" else "codex"
    # Do not accidentally turn subscription workflows into API-key billing.
    if any(os.environ.get(k) for k in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_AUTH_TOKEN")):
        raise ValueError("API 인증 환경변수가 설정되어 있습니다. 구독 전용 터미널에서 실행하세요.")
    if agent == "claude":
        usage_gate(config["usage_file"], config["usage_max_age_seconds"])
    binary = executable(config[agent + "_command"])
    output = directory / "result.json"
    schema = directory / "schema.json"
    save(schema, SCHEMA)
    if agent == "claude":
        args = [binary, "-p", "--output-format", "json", "--json-schema", json.dumps(SCHEMA),
                "--permission-mode", "acceptEdits", "--permission-prompts", "none", "--no-session-persistence"]
        allowed = config.get("claude_allowed_tools", [])
        if allowed:
            args += ["--allowedTools", ",".join(allowed)]
    else:
        args = [binary, "exec", "--ephemeral", "--sandbox", "read-only", "--output-schema", str(schema),
                "--output-last-message", str(output), "-"]
        # Optional per-child Windows implementation; keep the read-only policy.
        windows_mode = config.get("codex_windows_sandbox")
        if windows_mode is not None:
            if windows_mode not in ("elevated", "unelevated"):
                raise ValueError("codex_windows_sandbox must be elevated or unelevated")
            args[2:2] = ["-c", 'windows.sandbox="' + windows_mode + '"']
    save(directory / "invocation.json", {"agent": agent, "stage": s["stage"], "revision": s["revision"]})
    prompt = prompt_for(s)
    (directory / "prompt.txt").write_text(prompt, encoding="utf-8")
    started_at = time.time()
    with (directory / "stdout.log").open("w", encoding="utf-8") as stdout, (directory / "stderr.log").open("w", encoding="utf-8") as stderr:
        proc = subprocess.Popen(args, cwd=repo, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                text=True, encoding="utf-8", shell=False, env=child_environment(config),
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            proc.communicate(prompt, timeout=config["step_timeout_seconds"])
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
            else:
                proc.kill()
            proc.wait()
            raise RuntimeError("실행 중단. 부분 변경을 확인한 후 명시적으로 재개하세요.")
    if proc.returncode:
        raise RuntimeError(f"{agent} 종료 코드 {proc.returncode}; 로그: {directory}")
    if agent == "claude":
        envelope = read(directory / "stdout.log")
        return evaluate_claude_envelope(envelope, agent, repo, started_at, directory)
    return read(output)

def run(store, config, runner=invoke):
    run_guard.limits(config)
    with ExitStack() as locks:
        # Protect different state directories that target the same checkout too.
        if config.get("project_dir"):
            locks.enter_context(lock(Path(config["project_dir"]).resolve() / "automation" / ".runtime-locks" / "runner.lock", timeout=0))
        locks.enter_context(lock(store.directory / "runner.lock", timeout=0))
        while True:
            selected = {}
            def claim(s):
                if s["running"]:
                    raise ValueError("중단된 실행 기록이 있습니다. 프로세스 종료 확인 후 recover를 사용하세요.")
                if s["active"] and s["stage"] in STEPS:
                    if not run_guard.claim(s, config, event):
                        return
                    s["running"] = {"id": uuid.uuid4().hex, "stage": s["stage"], "revision": s["revision"]}
                    selected.update(json.loads(json.dumps(s)))
            claimed = store.update(claim)
            if not selected:
                if claimed.get('run_guard', {}).get('latched'):
                    print('[중단] ' + claimed['run_guard']['reason'], flush=True)
                return
            attempt = store.directory / "attempts" / selected["running"]["id"]
            attempt.mkdir(parents=True)
            try:
                result = validate(runner(config, selected, attempt))
                save(attempt / "validated-result.json", result)
                def finish(s):
                    s["running"] = None
                    if s["revision"] != selected["revision"]:
                        s["active"] = False
                        event(s, "attention", "요구사항 버전이 변경되어 결과를 채택하지 않았습니다.")
                        return
                    s["results"].append({"stage": selected["stage"], "revision": s["revision"], "attempt": str(attempt), **result})
                    transition(s, result)
                    run_guard.record(s, selected, result['outcome'], config, event)
                state = store.update(finish)
                print(f"{selected['stage']} -> {state['stage']} ({result['outcome']})", flush=True)
            except Exception as exc:
                def fail(s):
                    s["running"], s["active"] = None, False
                    event(s, "attention", str(exc))
                    run_guard.record(s, selected, 'error', config, event)
                store.update(fail)
                raise

def main():
    p = argparse.ArgumentParser(description="한자리 로컬 에이전트 제어기 — 기본 모드는 mock")
    p.add_argument("command", choices=["init", "status", "run", "pause", "resume", "approve-plan", "revise", "note", "recover", "review-pack", "reset-breaker"])
    p.add_argument("--config", type=Path, default=ROOT / "config.json")
    p.add_argument("--state-dir", type=Path, default=ROOT / ".runtime")
    p.add_argument("--task", type=Path)
    p.add_argument("--mode", choices=["mock", "live"], default="mock")
    p.add_argument("--activate", action="store_true", help="이번 작업 흐름의 실행을 명시적으로 활성화")
    p.add_argument("--text")
    args = p.parse_args()
    store = Store(args.state_dir.resolve())
    if args.command == "init":
        if not args.task:
            p.error("--task 필요")
        task = read(args.task)
        if not task.get("title") or not task.get("acceptance_criteria"):
            raise ValueError("title과 acceptance_criteria가 필요합니다.")
        with lock(store.directory / "state.lock"):
            if store.path.exists():
                raise ValueError("기존 작업을 덮어쓰지 않습니다. 다른 --state-dir를 지정하세요.")
            save(store.path, new_state(task, args.mode))
    elif args.command == "status":
        print(json.dumps(read(store.path), ensure_ascii=False, indent=2))
    elif args.command == "recover":
        raise ValueError("자동 잠금 제거는 지원하지 않습니다. runner PID 종료를 확인하고 runner.lock/state.lock을 제거한 뒤 state.json의 running을 null로 복구하세요. 중복 실행을 피하기 위한 수동 복구 절차입니다.")
    elif args.command == "review-pack":
        s = read(store.path)
        report = [f"# {s['title']}", f"Mode: {s['mode']} / Revision: {s['revision']} / Stage: {s['stage']}"]
        for r in s["results"]:
            report += [f"\n## {r['stage']} (revision {r['revision']})", r["summary"], "\n검증 근거:"]
            report += ["- " + x for x in r["evidence"]]
            report += ["\n관련 파일·읽는 순서:"] + ["- " + x for x in r["review_files"]]
        target = store.directory / "review-pack.md"
        target.write_text("\n".join(report), encoding="utf-8")
        print(target)
    else:
        def control(s):
            if args.command == "reset-breaker":
                run_guard.reset(s, args.text, event)
            elif args.command == "pause":
                s["active"] = False
                event(s, "pause", "현재 단계 종료 후 정지. 실행 중인 프로세스는 강제 종료하지 않습니다.")
            elif args.command == "note":
                if not args.text:
                    raise ValueError("--text 필요")
                event(s, "idea", args.text)
            elif args.command == "revise":
                if s["running"]:
                    raise ValueError("먼저 pause하고 현재 단계 종료를 기다리세요.")
                if not args.task:
                    raise ValueError("--task 필요")
                task = read(args.task)
                if not task.get("title") or not task.get("acceptance_criteria"):
                    raise ValueError("title과 acceptance_criteria가 필요합니다.")
                s.update(task=task, title=task["title"], revision=s["revision"]+1, stage="plan", active=False, repairs=0, manager_used=False)
                event(s, "revision", "요구사항 변경. 기존 검증은 이 버전에 적용되지 않습니다.")
            elif args.command == "approve-plan":
                if s["stage"] != "awaiting_plan_approval" or s["running"]:
                    raise ValueError("기획 승인 대기 상태가 아닙니다.")
                s["stage"] = "implement"
                event(s, "approval", "사용자 기획 승인")
            elif args.command in ("run", "resume"):
                if run_guard.state_guard(s)['latched']:
                    raise ValueError('반복 방지 중단 상태입니다. 원인 해결 후 reset-breaker --text 로 사유를 기록하세요.')
                if not args.activate:
                    raise ValueError("수동 활성화가 필요합니다: --activate")
                if s["stage"] not in STEPS:
                    raise ValueError(f"사용자 판단 대기 또는 종료 단계: {s['stage']}")
                if s["running"]:
                    raise ValueError("이미 실행 중이거나 복구가 필요합니다.")
                s["active"] = True
                event(s, "activation", "사용자 수동 활성화")
        store.update(control)
        if args.command in ("run", "resume"):
            try:
                run(store, read(args.config))
                stopped = read(store.path).get('run_guard', {})
                if stopped.get('latched'):
                    raise ValueError(stopped['reason'])
            finally:
                # Activation never survives a completed/failed runner invocation.
                # An active competing runner must not be altered here.
                if not (store.directory / "runner.lock").exists():
                    store.update(lambda s: s.update(active=False))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"중단: {error}", file=sys.stderr)
        sys.exit(1)
