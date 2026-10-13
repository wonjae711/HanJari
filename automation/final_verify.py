"""HJ-001: deterministic Maven verification followed by read-only Codex assessment."""
import argparse
import os
from pathlib import Path
import subprocess
import time
import controller as c

from verification_evidence import EXPECTED, source_manifest, check_reports, task_policy


def verify_then_assess(config, state, directory):
    if state['mode'] != 'live' or state['stage'] != 'final_test':
        raise ValueError('Only live final_test is supported')
    repo = Path(config['project_dir']).resolve()
    branch = subprocess.check_output(['git', 'branch', '--show-current'], cwd=repo, text=True).strip()
    if not branch or branch in ('main', 'master', config.get('base_branch', 'main')):
        raise ValueError('A feature branch is required')
    for name in ('HANJARI_TEST_DB_URL', 'HANJARI_TEST_DB_USERNAME', 'HANJARI_TEST_DB_PASSWORD'):
        if not os.environ.get(name):
            raise ValueError('Missing environment variable: ' + name)
    before = source_manifest(repo)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    started = time.time()
    with (directory / 'maven.log').open('w', encoding='utf-8') as log:
        proc = subprocess.Popen([str(repo / 'mvnw.cmd'), '-B', 'verify'], cwd=repo,
                                stdout=log, stderr=subprocess.STDOUT, shell=False,
                                env={k: v for k, v in os.environ.items() if k != "HANJARI_NOTION_TOKEN"},
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            code = proc.wait(timeout=config['step_timeout_seconds'])
        except BaseException:
            if os.name == 'nt':
                subprocess.run(['taskkill', '/PID', str(proc.pid), '/T', '/F'], capture_output=True)
            else:
                proc.kill()
            proc.wait()
            raise
    if code != 0:
        raise ValueError(f'Maven verify failed ({code}); inspect {directory / "maven.log"}')
    suites = check_reports(repo, started, task_policy(state['task']))
    if source_manifest(repo) != before:
        raise ValueError('Source changed during verification; result rejected')
    proof = {'command': 'mvnw.cmd -B verify', 'exit_code': code, 'started_at_epoch': started,
             'finished_at': c.now(), 'commit': commit, 'source_sha256': before, 'suites': suites,
             'test_count': sum(s['tests'] for s in suites),
             'limits': 'MockMvc plus real MySQL; no separate HTTP server, load test, or CI run.'}
    c.save(directory / 'verification.json', proof)
    print(f"Maven verify: {proof['test_count']} tests passed. Starting Codex assessment.", flush=True)
    # Give the reviewer current evidence, not a prior agent's claim of success.
    state['task'] = dict(state['task'])
    state['task']['current_final_verification'] = {
        'file': str(directory / 'verification.json'),
        'instructions': 'The controller just executed Maven verify for this final_test attempt. Read verification.json and actual source/test files, map all acceptance criteria to those tests, and assess whether the fresh execution satisfies them. Do not rerun Maven in your read-only sandbox. Do not read environment values or raw XML properties. Do not claim you personally executed tests or tested a running HTTP server. Return blocked/changes if evidence is insufficient. No code or state edits.'}
    # Database credentials are needed by Maven, not by the read-only reviewer.
    private = {k: os.environ.pop(k) for k in list(os.environ) if k.startswith('HANJARI_')}
    try:
        result = c.invoke(config, state, directory)
    finally:
        os.environ.update(private)
    if source_manifest(repo) != before:
        raise ValueError('Source changed during final assessment; result rejected')
    return result

def select_store(recheck):
    original = c.Store(c.ROOT / '.runtime-pilot')
    if not recheck:
        return original
    source = c.read(original.path)
    if source['stage'] != 'pr_ready' or source['active'] or source['running']:
        raise ValueError('Recheck requires the original pilot to be complete and idle')
    store = c.Store(c.ROOT / '.runtime-recheck')
    with c.lock(store.directory / 'state.lock'):
        if not store.path.exists():
            state = c.new_state(dict(source['task']), 'live')
            state.update(stage='final_test', revision=source['revision'], recheck_of=source['id'])
            c.event(state, 'recheck', 'User requested one verification-only replay; original state preserved.')
            c.save(store.path, state)
    return store

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--activate', action='store_true')
    parser.add_argument('--recheck', action='store_true', help='One verification-only replay, preserving the completed pilot')
    args = parser.parse_args()
    if not args.activate:
        parser.error('--activate is required')
    store = select_store(args.recheck)
    config = c.read(c.ROOT / 'config.json')
    def activate(s):
        if c.run_guard.state_guard(s)['latched']:
            raise ValueError('Breaker is latched; inspect and explicitly reset it before resuming')
        if s['stage'] != 'final_test' or s['running'] or s['active']:
            raise ValueError('Expected an idle final_test stage')
        s['active'] = True
        c.event(s, 'activation', 'User activated deterministic final verification and Codex assessment')
    def one_stage(cfg, state, directory):
        try:
            return verify_then_assess(cfg, state, directory)
        finally:
            store.update(lambda s: s.update(active=False))
    store.update(activate)
    try:
        c.run(store, config, runner=one_stage)
    finally:
        if not (store.directory / 'runner.lock').exists():
            store.update(lambda s: s.update(active=False))
    state = c.read(store.path)
    print('Stage:', state['stage'], '/ active:', state['active'])
    if state['results']:
        print(state['results'][-1]['summary'])
    if state['stage'] != 'pr_ready':
        raise RuntimeError('Final assessment did not pass; inspect the saved result before resuming')

if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('Stopped:', exc)
        raise SystemExit(1)
