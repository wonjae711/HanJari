"""Manual project planning, exact-plan approval, and one-feature handoff."""
import argparse
import json
from functools import wraps
from pathlib import Path
import controller as c
import project_planning as p
import knowledge

SOURCE_PAGE = '3f4d0420-a262-8139-85ac-c8fb6aa13ea6'
CONFIRMED = [
    '영화/회차/좌석 선택부터 모의 결제와 예매 내역까지의 MVP. 실제 금전 거래 없음.',
    '중복 예매 방지, 확보 자동 만료, 중복 요청 처리 필수.',
    '조회는 비회원 가능. 좌석 확보부터 로그인. 가입/로그인/로그아웃과 본인 내역 접근.',
    '소셜 로그인/이메일 인증/비밀번호 찾기/랭킹/알림/Kafka는 후속.',
    '5분/1~4석/전체 취소/운영 API 등 전체 범위안의 나머지 정책은 제안이지 사용자 확정이 아니다.',
    '기획/독립 검토는 Codex, 개발/개발 테스트는 Claude Code, 독립 리뷰/최종 판정은 Codex.',
    '수동 활성화. 기획 승인과 최종 PR 병합 판단은 사용자. 부하 테스트는 사용자가 직접 수행.',
]

def exclusive_project(fn):
    @wraps(fn)
    def wrapped(store, *args, **kwargs):
        with c.lock(store.directory / 'project.lock', timeout=0):
            return fn(store, *args, **kwargs)
    return wrapped

def initialize(store, mode, source=None):
    if store.path.exists(): return
    source = source if source is not None else knowledge.snapshot(SOURCE_PAGE)
    task = {'kind': 'project_plan', 'task_id': 'HJ-PLAN', 'title': '한자리 MVP 전체 기획',
            'requirements': '기획자와 독립 검토자가 전체 기획과 작은 개발 업무들을 작성한다.',
            'confirmed_constraints': CONFIRMED, 'source_snapshot': source,
            'acceptance_criteria': ['모든 확정 요구 추적', '검증 가능한 기능별 기준', '독립 검토 후 사용자 승인']}
    with c.lock(store.directory / 'state.lock'):
        if store.path.exists(): return
        c.save(store.path, c.new_state(task, mode))

def source_unchanged(state):
    if state['mode'] == 'mock': return
    source = state['task']['source_snapshot']
    fresh = knowledge.snapshot(source['page_id'])
    if fresh['sha256'] != source['sha256']:
        raise ValueError('Notion source changed. Use project.py revise --text to start a new revision.')

@exclusive_project
def approve(store, expected_hash, config):
    initial = c.read(store.path)
    source_unchanged(initial)
    def commit(s):
        if s['active'] or s['running'] or s['stage'] != 'awaiting_plan_approval':
            raise ValueError('An idle, reviewed project plan is required')
        actual = p.plan_hash(s)
        if not expected_hash or expected_hash != actual or actual != s.get('candidate_hash'):
            raise ValueError('Approval hash differs from the reviewed plan')
        if p.candidate(s)['questions']: raise ValueError('Unanswered planning questions')
        s['approved_plan'] = {'hash': actual, 'revision': s['revision'], 'at': c.now()}
        c.event(s, 'approval', 'User approved project plan ' + actual)
    state = store.update(commit)
    export(store, state)
    c.deliver_events(store, config)

def export(store, state=None):
    state = state or c.read(store.path)
    p.export_artifacts(store, state)
    if state.get('approved_plan', {}).get('hash') != p.plan_hash(state):
        raise ValueError('Approve current plan before exporting development tasks')
    target = store.directory / 'handoff'
    target.mkdir(parents=True, exist_ok=True)
    for feature in p.candidate(state)['features']:
        c.save(target / (feature['task_id'] + '.task.json'), p.feature_task(state, feature))

@exclusive_project
def revise(store, text):
    if not text or not text.strip(): raise ValueError('Revision instructions required')
    before = c.read(store.path)
    if before['active'] or before['running']: raise ValueError('Pause and wait for current work to finish')
    for path in before.get('handoffs', {}).values():
        child = c.read(path)
        if child['active'] or child['running']: raise ValueError('Pause active development before revising project')
    fresh = before['task']['source_snapshot'] if before['mode'] == 'mock' else knowledge.snapshot(SOURCE_PAGE)
    def change(s):
        if s['active'] or s['running'] or s['revision'] != before['revision']:
            raise ValueError('State changed during revision')
        # Previously completed feature states remain immutable historical evidence.
        s.setdefault('project_revision_history', []).append({
            'revision': s['revision'], 'task': s['task'], 'approved_plan': s.get('approved_plan'),
            'handoffs': s.get('handoffs', {}), 'accepted_features': s.get('accepted_features', {})})
        s['task'] = dict(s['task'], source_snapshot=fresh,
                         steering=s['task'].get('steering', []) + [text.strip()])
        s.update(revision=s['revision']+1, stage='plan', active=False,
                 planning_repairs=0, planning_manager_used=False, handoffs={}, accepted_features={})
        for key in ('candidate_hash', 'approved_plan'): s.pop(key, None)
        c.event(s, 'revision', text.strip())
    store.update(change)

def verify_handoff(child):
    origin = child.get('project_handoff')
    if not origin: raise ValueError('Feature has no project approval provenance')
    parent = c.read(Path(origin['state_path']))
    current_hash = p.plan_hash(parent)
    if parent.get('approved_plan', {}).get('hash') != current_hash or origin['hash'] != current_hash:
        raise ValueError('Project approval changed; this feature needs a new handoff')
    feature = next((f for f in p.candidate(parent)['features'] if f['task_id'] == child['task']['task_id']), None)
    if feature is None or p.feature_task(parent, feature) != child['task']:
        raise ValueError('Feature task differs from the approved project artifact')
    if child['mode'] != parent['mode']:
        raise ValueError('Mock plans may not authorize live development')

@exclusive_project
def handoff(store, task_id):
    # Serialize against project run/revision; creating a child never starts an agent.
    with c.lock(store.directory / 'runner.lock', timeout=0):
        state = c.read(store.path)
        if state['active'] or state['running']: raise ValueError('Project is still running')
        plan_hash = p.plan_hash(state)
        if state.get('approved_plan', {}).get('hash') != plan_hash:
            raise ValueError('Project plan requires exact approval first')
        source_unchanged(state)
        features = p.candidate(state)['features']
        feature = next((f for f in features if f['task_id'] == task_id), None)
        if feature is None: raise ValueError('Unknown feature')
        for earlier in features[:features.index(feature)]:
            if state.get('accepted_features', {}).get(earlier['task_id'], {}).get('plan_hash') != plan_hash:
                raise ValueError('Human acceptance of earlier features is required before the next handoff')
        child = c.Store(store.directory / 'features' / ('r' + str(state['revision'])) / task_id)
        with c.lock(child.directory / 'state.lock'):
            if child.path.exists():
                existing = c.read(child.path)
                verify_handoff(existing)
            else:
                task = p.feature_task(state, feature)
                created = c.new_state(task, state['mode'])
                created.update(stage='implement', project_handoff={
                    'state_path': str(store.path), 'hash': plan_hash})
                c.event(created, 'approval', 'Inherited user-approved feature scope from ' + plan_hash)
                c.save(child.path, created)
        store.update(lambda s: (s.setdefault('handoffs', {}).update({task_id: str(child.path)}),
                               c.event(s, 'handoff', task_id + ' prepared; inactive')))
        return child

@exclusive_project
def accept_feature(store, task_id, reason):
    if not reason or not reason.strip(): raise ValueError('Human review conclusion required')
    parent = c.read(store.path)
    if task_id not in parent.get('handoffs', {}): raise ValueError('Feature has not been handed off')
    child = c.read(parent['handoffs'][task_id])
    verify_handoff(child)
    if child['stage'] != 'pr_ready' or child['active'] or child['running']:
        raise ValueError('Only a completed, inactive feature may be accepted')
    def accept(s):
        s.setdefault('accepted_features', {})[task_id] = {'plan_hash': p.plan_hash(s), 'at': c.now(), 'reason': reason}
        c.event(s, 'human_accept', task_id + ': ' + reason)
    store.update(accept)

def run_project(store, config):
    state = c.read(store.path)
    source_unchanged(state)
    def activate(s):
        if s['stage'] not in ('plan', 'spec_review', 'manager') or s['active'] or s['running']:
            raise ValueError('Project is complete, awaiting approval, or already running')
        if c.run_guard.state_guard(s)['latched']: raise ValueError('Breaker requires explicit reset with reason')
        s['active'] = True
        c.event(s, 'activation', 'User activated project planning')
    # Controller owns the shared repository lock and runner lock during execution.
    store.update(activate)
    try: c.run(store, config)
    finally:
        if not (store.directory / 'runner.lock').exists():
            store.update(lambda s: s.update(active=False))
    state = c.read(store.path)
    print('Stage:', state['stage'], '/ active:', state['active'])
    if state.get('candidate_hash'): print('Approval hash:', state['candidate_hash'])

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['run', 'status', 'approve', 'revise', 'export', 'handoff', 'accept-feature'])
    parser.add_argument('--state-dir', type=Path, default=c.ROOT / '.runtime-project')
    parser.add_argument('--config', type=Path, default=c.ROOT / 'config.json')
    parser.add_argument('--mode', choices=['mock', 'live'], default='live')
    parser.add_argument('--activate', action='store_true')
    parser.add_argument('--hash')
    parser.add_argument('--text')
    parser.add_argument('--feature')
    args = parser.parse_args()
    store = c.Store(args.state_dir.resolve())
    config = c.read(args.config)
    if args.command == 'run':
        if not args.activate: raise ValueError('--activate required')
        source = {'page_id': SOURCE_PAGE, 'text': 'MOCK source', 'sha256': 'mock'} if args.mode == 'mock' else None
        initialize(store, args.mode, source)
        if c.read(store.path)['mode'] != args.mode: raise ValueError('Existing state mode differs; use a separate state directory')
        run_project(store, config)
    elif args.command == 'status':
        state = c.read(store.path)
        print(json.dumps({k: state.get(k) for k in ('stage', 'active', 'running', 'revision', 'candidate_hash', 'approved_plan', 'planning_repairs', 'planning_manager_used', 'run_guard')}, ensure_ascii=False, indent=2))
    elif args.command == 'approve': approve(store, args.hash, config)
    elif args.command == 'revise': revise(store, args.text)
    elif args.command == 'export': export(store)
    elif args.command == 'handoff':
        child = handoff(store, args.feature)
        print('Inactive feature state:', child.directory)
        print('Run with controller.py run --state-dir <above> --activate')
    else: accept_feature(store, args.feature, args.text)
    if args.command in ('revise', 'handoff', 'accept-feature'): c.deliver_events(store, config)

if __name__ == '__main__':
    try: main()
    except Exception as exc:
        # Remote exceptions may carry sensitive response bodies; do not serialize them.
        import urllib.error
        if isinstance(exc, (urllib.error.URLError, c.subprocess.SubprocessError)):
            print('Project stopped: external access failed. Check permissions/authentication.')
        else: print('Project stopped:', str(exc))
        if '--activate' in __import__('sys').argv:
            try:
                import delivery
                delivery.send_notification({'snapshot': {'task_id': 'HJ-PLAN', 'stage': 'needs_user'}},
                                           c.read(c.ROOT / 'integrations.json'))
            except Exception:
                print('Windows notification unavailable; inspect the console error.')
        raise SystemExit(1)
