"""One manually activated entry point with human decisions between bounded runs."""
import argparse
import copy
import json
import os
import re
import subprocess
import sys
from pathlib import Path
import controller as c
import project
import project_planning as planning
import project_profiles as profiles

REGISTRY = c.ROOT / '.runtime-workspaces'

def load_profile(key, shared, registry=REGISTRY):
    if key == 'hanjari': return profiles.hanjari(shared)
    if not re.fullmatch(r'[a-z][a-z0-9-]{1,39}', key): raise ValueError('Invalid project ID')
    return profiles.validate(c.read(registry / key / 'profile.json'))

def register(args, registry=REGISTRY):
    def ask(value, prompt): return value or input(prompt).strip()
    key = ask(args.project, 'Project ID (lowercase, e.g. bookshelf): ')
    if key == 'hanjari': raise ValueError('HanJari is already registered')
    repo, remote = profiles.repository(ask(args.repo, 'Repository folder: '))
    if remote == 'wonjae711/hanjari': raise ValueError('Use the existing hanjari project profile')
    idea = Path(args.idea_file).read_text(encoding='utf-8-sig') if args.idea_file else ask(args.idea, 'Project idea: ')
    title = ask(args.title, 'Project title: ')
    baseline = c.read(args.baseline) if args.baseline else copy.deepcopy(profiles.EMPTY_BASELINE)
    if (repo / 'src/test').exists() and not args.baseline:
        raise ValueError('Existing project: supply --baseline with current required Maven suites')
    profile = profiles.validate(dict(id=key, title=title, prefix=args.prefix or 'TASK', stack=profiles.STACK,
        project_dir=str(repo), remote=remote, state_dir=str((registry / key / 'live').resolve()),
        source_page=args.source_page, record_page=ask(args.record_page, 'Notion output page ID (separate from source): '),
        idea=idea, constraints=[], baseline=baseline, first_feature=1,
        test_db_env=['APP_TEST_DB_URL', 'APP_TEST_DB_USERNAME', 'APP_TEST_DB_PASSWORD']))
    target = registry / key / 'profile.json'
    with c.lock(registry / 'register.lock'):
        if target.exists(): raise ValueError('Project already registered; existing configuration preserved')
        for other in registry.glob('*/profile.json'):
            saved = c.read(other)
            if Path(saved['project_dir']).resolve() == repo or saved['remote'] == remote:
                raise ValueError('Repository already registered under another project')
        c.save(target, profile)
    print('Registered:', key, '/ no agent started')
    return profile

def setup(profile, shared, mode='live'):
    config = profiles.configuration(profile, shared)
    directory = Path(profile['state_dir'])
    if mode == 'mock': directory = directory.with_name(directory.name + '-mock')
    store = c.Store(directory)
    if mode == 'mock': config['integration_delivery'] = False
    if store.path.exists():
        state = c.read(store.path)
        if state['mode'] != mode: raise ValueError('Mode mismatch')
        # Preserve old HanJari approval hashes and history without migration.
        if profile['id'] == 'hanjari' and 'project_context' not in state['task']:
            config.pop('project_context')
        profiles.check_binding(state, config)
    return store, config

def pending_feature(parent):
    plan_hash = planning.plan_hash(parent)
    return next((f for f in planning.candidate(parent)['features']
                 if parent.get('accepted_features', {}).get(f['task_id'], {}).get('plan_hash') != plan_hash), None)

def current_store(parent_store):
    parent = c.read(parent_store.path)
    feature = pending_feature(parent) if parent.get('approved_plan') else None
    path = parent.get('handoffs', {}).get(feature['task_id']) if feature else None
    return c.Store(Path(path).parent) if path else parent_store

def review_pack(store, config):
    state = c.read(store.path)
    lines = [f"# {state['task']['task_id']} — 사용자 코드 리뷰", f"Mode: {state['mode']}",
             'Draft PR 준비 자료입니다. 자동 병합하지 않습니다.',
             '## 승인 범위', state['task']['requirements'], '## 인수 기준']
    lines += ['- ' + v for v in state['task']['acceptance_criteria']]
    for result in state['results']:
        lines += ['## ' + result['stage'], result['summary'], '### 검증 근거']
        lines += ['- ' + v for v in result['evidence']]
        lines += ['### 관련 파일·읽는 순서'] + ['- ' + v for v in result['review_files']]
    if state['mode'] == 'live':
        lines += ['## 현재 변경 파일', '```', profiles.git(config['project_dir'], 'status', '--short'), '```']
    target = store.directory / 'review-pack.md'
    target.write_text('\n\n'.join(lines), encoding='utf-8')
    print('Code review:', target)

def prepare_branch(child, config):
    state = c.read(child.path)
    if state['mode'] == 'mock': return
    repo = config['project_dir']
    branch = profiles.git(repo, 'branch', '--show-current')
    saved = state.get('work_branch')
    if saved:
        if branch != saved: raise ValueError('Return to feature branch ' + saved + ' before resuming')
        return
    if profiles.git(repo, 'status', '--porcelain'):
        raise ValueError('First feature execution requires a clean checkout; preserve/commit existing work first')
    if not branch: raise ValueError('Detached checkout; select a branch first')
    if branch in ('main', 'master', config.get('base_branch', 'main')):
        branch = 'codex/' + config.get('project_context', {}).get('id', 'hanjari') + '-' + state['task']['task_id'].lower()
        subprocess.run(['git', '-C', repo, 'checkout', '-b', branch], check=True, capture_output=True)
    child.update(lambda s: s.update(work_branch=branch))

def run_once(store, profile, config, mode):
    if not store.path.exists():
        source = {'kind': 'local_idea', 'text': profile['idea'], 'sha256': planning.digest(profile['idea'])} if mode == 'mock' else None
        project.initialize(store, mode, source, profile=profile)
    parent = c.read(store.path)
    profiles.check_binding(parent, config)
    store.update(lambda s: s.update(pause_requested=False))
    if not parent.get('approved_plan'):
        if parent['stage'] in ('awaiting_plan_approval', 'needs_user'):
            show_status(store); return
        project.run_project(store, config)
        show_status(store)
        return
    project.source_unchanged(parent)
    feature = pending_feature(parent)
    if feature is None:
        print('All approved features accepted. Final merge/release remains a human decision.'); return
    child = project.handoff(store, feature['task_id'])
    state = c.read(child.path)
    if state['stage'] == 'pr_ready':
        review_pack(child, config)
        print('Waiting for human code review. No agent restarted.'); return
    if state['stage'] == 'needs_user': show_status(store); return
    prepare_branch(child, config)
    if state['mode'] == 'live':
        missing = [name for name in config['test_db_env'] if not os.environ.get(name)]
        if missing: raise ValueError('Prepare dedicated test DB credentials in this terminal: ' + ', '.join(missing))
    def activate(s):
        if s['active'] or s['running']: raise ValueError('Feature already active; do not duplicate execution')
        if c.run_guard.state_guard(s)['latched']: raise ValueError('Breaker latched; resolve cause and explicitly reset')
        if s['stage'] not in c.STEPS: raise ValueError('Human decision required')
        s['active'] = True
        c.event(s, 'activation', 'User activated approved feature ' + feature['task_id'])
    child.update(activate)
    try: c.run(child, config)
    finally:
        if not (child.directory / 'runner.lock').exists(): child.update(lambda s: s.update(active=False))
    if c.read(child.path)['stage'] == 'pr_ready': review_pack(child, config)
    show_status(store)

def show_status(store):
    if not store.path.exists(): print('Not started. run --activate starts planning.'); return
    parent = c.read(store.path)
    selected = c.read(current_store(store).path)
    print(json.dumps({k: selected.get(k) for k in ('title', 'mode', 'stage', 'active', 'running', 'revision', 'run_guard')}, ensure_ascii=False, indent=2))
    if parent.get('candidate_hash') and not parent.get('approved_plan'):
        print('Review plan artifacts:', store.directory / 'artifacts')
        print('Approve exact plan with --hash', parent['candidate_hash'])
    if selected.get('results'):
        last = selected['results'][-1]
        print(last['summary'])
        for question in last.get('artifact', {}).get('questions', []): print('Decision:', question)

def main(argv=None):
    parser = argparse.ArgumentParser(description='Idea → planning review → human decisions → implementation → code review')
    parser.add_argument('command', choices=['register', 'run', 'status', 'approve', 'revise', 'note', 'pause', 'accept-feature', 'reset-breaker', 'sync', 'retry-sync'])
    parser.add_argument('--project')
    parser.add_argument('--config', type=Path, default=c.ROOT / 'config.json')
    parser.add_argument('--mode', choices=['live', 'mock'], default='live')
    parser.add_argument('--activate', action='store_true')
    for name in ('repo', 'title', 'prefix', 'idea', 'idea-file', 'source-page', 'record-page', 'baseline', 'hash', 'feature', 'text'):
        parser.add_argument('--' + name)
    args = parser.parse_args(argv)
    if args.command == 'register': register(args); return
    profile = load_profile(args.project or 'hanjari', c.read(args.config))
    store, config = setup(profile, c.read(args.config), args.mode)
    if args.command == 'status': show_status(store); return
    if args.command in ('run', 'sync') and not args.activate: raise ValueError('--activate required')
    if args.command == 'run':
        with c.lock(store.directory / 'workspace.lock', timeout=0):
            run_once(store, profile, config, args.mode)
        return
    if not store.path.exists(): raise ValueError('Start planning first')
    target = current_store(store)
    if args.command == 'approve': project.approve(store, args.hash, config)
    elif args.command == 'revise': project.revise(store, args.text)
    elif args.command == 'accept-feature': project.accept_feature(store, args.feature, args.text)
    elif args.command == 'pause':
        store.update(lambda s: s.update(pause_requested=True, active=False))
        target.update(lambda s: (s.update(active=False), c.event(s, 'pause', 'User paused; current step may finish')))
    elif args.command == 'note':
        if not args.text or not args.text.strip(): raise ValueError('--text required')
        store.update(lambda s: c.event(s, 'idea', args.text))
        print('Idea recorded. Use pause then revise to incorporate it into approved scope.')
    elif args.command == 'reset-breaker': target.update(lambda s: c.run_guard.reset(s, args.text, c.event))
    elif args.command == 'retry-sync':
        if not args.text or not args.text.strip(): raise ValueError('Retry reason required in --text')
        def retry(s):
            for receipt in s.get('knowledge_receipts', {}).values():
                if receipt['status'] == 'needs_user':
                    receipt.setdefault('history', []).append({'attempts': receipt['attempts'], 'reason': args.text})
                    receipt.update(status='pending', attempts=0)
            for item in s.get('outbox', []):
                for channel, receipt in item['channels'].items():
                    if receipt['status'] == 'needs_user': c.outbox.retry(s, item['id'], channel, args.text)
        for entry in {str(store.directory): store, str(target.directory): target}.values(): entry.update(retry)
        print('Delivery retry enabled. Run sync --activate; no agents started.')
        return
    c.deliver_events(store, config)
    if target.path != store.path: c.deliver_events(target, config)

if __name__ == '__main__':
    try: main()
    except Exception as exc:
        from urllib.error import URLError
        if isinstance(exc, (subprocess.SubprocessError, URLError)):
            print('Stopped: external access failed; check Git/Notion permissions.', file=sys.stderr)
        else: print('Stopped:', str(exc), file=sys.stderr)
        if '--activate' in sys.argv:
            try:
                import delivery
                delivery.send_notification({'snapshot': {'task_id': 'Workspace', 'stage': 'needs_user'}}, c.read(c.ROOT / 'integrations.json'))
            except Exception: print('Notification unavailable; see console.', file=sys.stderr)
        raise SystemExit(1)
