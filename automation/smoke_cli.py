"""Explicit opt-in, read-only CLI transport smoke test. No project work."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import controller as c

def verify_read(result, expected):
    c.validate(result)
    # Shell output may add a terminal CR/LF. Do not mask spaces or other changes.
    if result["outcome"] != "pass" or result["summary"].rstrip("\r\n") != expected.rstrip("\r\n"):
        raise RuntimeError("실제 파일 읽기 응답 불일치")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("agent", choices=["claude", "codex"])
    p.add_argument("--activate", action="store_true")
    args = p.parse_args()
    if not args.activate:
        p.error("실제 구독 호출에는 --activate가 필요합니다.")
    config = c.read(c.ROOT / "config.json")
    if any(os.environ.get(k) for k in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_AUTH_TOKEN")):
        raise ValueError("API 인증 환경변수가 설정되어 있습니다.")
    # Respect quota values without testing/modifying the statusline integration here.
    if args.agent == "claude":
        c.usage_gate(config["usage_file"], config["usage_max_age_seconds"])
    target = c.ROOT / ".runtime-cli-smoke" / args.agent
    target.mkdir(parents=True, exist_ok=True)
    fixture = target / "fixture.txt"
    fixture.write_text("HANJARI_READ_ONLY_PROBE_42", encoding="utf-8")
    output = target / "response.json"
    output.unlink(missing_ok=True)
    c.save(target / "schema.json", c.SCHEMA)
    prompt = ('Read only fixture.txt in the working directory. Do not modify any file. '
              'Do not use network, MCP tools or subagents. Return JSON with outcome=pass, '
              'summary equal to the exact contents of fixture.txt, evidence=["Read fixture.txt"], '
              'review_files=["fixture.txt"]. If unable to read, return blocked.')
    binary = c.executable(config[args.agent + "_command"])
    if args.agent == "claude":
        command = [binary, "-p", "--output-format", "json", "--json-schema", json.dumps(c.SCHEMA),
                   "--tools", "Read", "--allowedTools", "Read", "--strict-mcp-config",
                   "--permission-prompts", "none", "--no-session-persistence"]
    else:
        command = [binary, "exec", "--skip-git-repo-check", "--sandbox", "read-only", "--ephemeral",
                   "--output-schema", str(target / "schema.json"), "--output-last-message", str(output), "-"]
    with (target / "stdout.log").open("w", encoding="utf-8") as stdout, (target / "stderr.log").open("w", encoding="utf-8") as stderr:
        proc = subprocess.Popen(command, cwd=target, env=c.child_environment(config), stdin=subprocess.PIPE,
                                stdout=stdout, stderr=stderr, text=True, encoding="utf-8", shell=False,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            proc.communicate(prompt, timeout=120)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
            else:
                proc.kill()
            proc.wait()
            raise RuntimeError(f"시간 초과/중단: {target}")
    if proc.returncode:
        raise RuntimeError(f"CLI 종료 코드 {proc.returncode}. 로그: {target}")
    if args.agent == "claude":
        envelope = c.read(target / "stdout.log")
        if envelope.get("is_error") or envelope.get("permission_denials"):
            raise RuntimeError("CLI 오류/권한 거부")
        result = envelope.get("structured_output")
    else:
        result = c.read(output)
    verify_read(result, fixture.read_text(encoding="utf-8"))
    c.save(target / "verified.json", {"agent": args.agent, "verified_at": c.now(), "result": result})
    print(args.agent + ": PASS - 실제 파일 읽기 및 JSON 결과 검증 성공")

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
