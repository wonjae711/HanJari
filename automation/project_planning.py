"""Project-wide planning artifacts and bounded independent review.

Only the controller writes artifacts. Model output cannot supply commands/paths.
"""
import copy
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STR = {'type': 'string'}
STRINGS = {'type': 'array', 'items': STR}

def obj(properties):
    return {'type': 'object', 'properties': properties,
            'required': list(properties), 'additionalProperties': False}

SUITE = obj({'name': STR, 'minimum_tests': {'type': 'integer'}})
FEATURE = obj({'task_id': STR, 'title': STR, 'requirements': STR,
               'acceptance_criteria': STRINGS, 'excluded': STRINGS,
               'depends_on': STRINGS,
               'unit_suites': {'type': 'array', 'items': SUITE},
               'integration_suites': {'type': 'array', 'items': SUITE}})
ARTIFACT = obj({'document': STR, 'decisions': STRINGS, 'questions': STRINGS,
                'findings': STRINGS, 'features': {'type': 'array', 'items': FEATURE}})

def is_project(state):
    return state.get('task', {}).get('kind') == 'project_plan'

def schema(base):
    result = copy.deepcopy(base)
    result['properties']['artifact'] = ARTIFACT
    result['required'].append('artifact')
    return result

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()

def validate_shape(value, spec):
    kind = spec['type']
    if kind == 'object':
        if not isinstance(value, dict) or set(value) != set(spec['required']):
            raise ValueError('Invalid planning artifact fields')
        for key, item in value.items(): validate_shape(item, spec['properties'][key])
    elif kind == 'array':
        if not isinstance(value, list) or len(value) > 100:
            raise ValueError('Invalid planning array')
        for item in value: validate_shape(item, spec['items'])
    elif kind == 'string':
        if not isinstance(value, str) or len(value) > 80000:
            raise ValueError('Invalid planning text')
    elif kind == 'integer' and (type(value) is not int or not 1 <= value <= 10000):
        raise ValueError('Invalid minimum test count')

def validate_features(features):
    from verification_evidence import EXPECTED
    seen = set()
    baseline = {n for suites in EXPECTED.values() for n in suites}
    if not features or len(features) > 30:
        raise ValueError('Plan needs 1..30 ordered features')
    for feature in features:
        key = feature['task_id']
        if not re.fullmatch(r'HJ-[0-9]{3,5}', key) or key == 'HJ-001' or key in seen:
            raise ValueError('Feature IDs must be unique HJ numbers, excluding pilot HJ-001')
        if not set(feature['depends_on']) <= seen:
            raise ValueError('Dependencies must refer to earlier features; cycles are prohibited')
        if not feature['title'].strip() or not feature['requirements'].strip() or not feature['acceptance_criteria']:
            raise ValueError('Feature scope and acceptance criteria required')
        names = set()
        for suites in (feature['unit_suites'], feature['integration_suites']):
            if not suites:
                raise ValueError('Each backend feature requires unit and real-DB integration coverage')
            for suite in suites:
                name = suite['name']
                if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:Test|IT)', name) or name in names or name in baseline:
                    raise ValueError('New feature needs distinct test suite names')
                names.add(name)
        seen.add(key)

def validate_result(result, state):
    validate_shape(result['artifact'], ARTIFACT)
    a = result['artifact']
    if state['stage'] == 'plan' and result['outcome'] == 'pass':
        if not a['document'].strip(): raise ValueError('Empty planning document')
        validate_features(a['features'])
    if state['stage'] == 'spec_review' and result['outcome'] == 'pass':
        if a['findings'] or a['questions']:
            raise ValueError('Reviewer cannot pass unresolved findings/questions')
        candidate(state)  # Require a plan from this revision.
    if state['stage'] != 'plan' and (a['document'] or a['features']):
        raise ValueError('Reviewer/manager must critique, not silently rewrite the plan')

def candidate(state):
    for result in reversed(state['results']):
        if result['revision'] == state['revision'] and result['stage'] == 'plan' and result['outcome'] == 'pass':
            return result['artifact']
    raise ValueError('No current planning candidate')

def plan_hash(state):
    return digest({'revision': state['revision'], 'task': state['task'], 'artifact': candidate(state)})

def transition(state, result, event):
    stage, outcome = state['stage'], result['outcome']
    if outcome == 'blocked':
        state['active'] = False
        event(state, 'attention', result['summary'])
        return
    if stage == 'manager':
        state['planning_manager_used'] = True
        if outcome == 'pass':
            state['stage'] = 'plan'
        else:
            state.update(stage='needs_user', active=False)
            event(state, 'attention', 'Management could not resolve planning; user decision required')
        return
    if outcome == 'changes':
        if state.get('planning_manager_used'):
            state.update(stage='needs_user', active=False)
        elif state.get('planning_repairs', 0) < 3:
            state['planning_repairs'] = state.get('planning_repairs', 0) + 1
            state['stage'] = 'plan'
        else:
            state['stage'] = 'manager'
        event(state, 'repair', 'Planning correction / ' + state['stage'])
        return
    if stage == 'plan':
        state['stage'] = 'spec_review'
    else:
        if candidate(state)['questions']:
            state.update(stage='needs_user', active=False)
            event(state, 'attention', 'Planning questions require user answers')
        else:
            state.update(stage='awaiting_plan_approval', active=False)
            state['candidate_hash'] = plan_hash(state)
            event(state, 'attention', 'Planning reviewed; approve the exact candidate hash')

def prompt(state):
    common = (ROOT / 'prompts/planning-common.md').read_text(encoding='utf-8')
    role = (ROOT / ('prompts/' + state['stage'] + '.md')).read_text(encoding='utf-8')
    current = [r for r in state['results'] if r['revision'] == state['revision']]
    # Latest draft + latest critique + manager advice, rather than all full drafts.
    recent = {}
    for item in current: recent[item['stage']] = item
    context = {'task': state['task'], 'revision': state['revision'],
               'planning_repairs': state.get('planning_repairs', 0),
               'previous_results': recent,
               'user_notes': [e['message'] for e in state['events'] if e['kind'] == 'idea'][-20:]}
    return common + '\n\n' + role + '\n\nINPUT DATA:\n' + json.dumps(context, ensure_ascii=False)

def mock_result(state):
    artifact = dict(document='', decisions=[], questions=[], findings=[], features=[])
    if state['stage'] == 'plan':
        artifact.update(document='# MOCK plan\nNot a real planning result.', features=[{
            'task_id': 'HJ-002', 'title': 'MOCK member feature', 'requirements': 'MOCK only',
            'acceptance_criteria': ['MOCK scenario'], 'excluded': ['live execution'], 'depends_on': [],
            'unit_suites': [{'name': 'MemberTest', 'minimum_tests': 1}],
            'integration_suites': [{'name': 'MemberIT', 'minimum_tests': 1}]}])
    return dict(outcome='pass', summary='MOCK ' + state['stage'], evidence=['MOCK only'],
                review_files=[], artifact=artifact)

def export_artifacts(store, state):
    """Local readable record; called after state commit and also by export command."""
    target = store.directory / 'artifacts'
    target.mkdir(parents=True, exist_ok=True)
    for index, result in enumerate(state['results']):
        name = f"{index+1:03d}-r{result['revision']}-{result['stage']}.md"
        a = result.get('artifact', {})
        body = [f"# {state['title']} / {result['stage']}",
                f"Mode: {state['mode']} / Revision: {result['revision']} / Outcome: {result['outcome']}",
                result['summary'], a.get('document', '')]
        for key in ('decisions', 'questions', 'findings'):
            body += ['\n## ' + key] + ['- ' + t for t in a.get(key, [])]
        body += ['\n## Features', json.dumps(a.get('features', []), ensure_ascii=False, indent=2),
                 '\n## Evidence'] + result['evidence']
        (target / name).write_text('\n\n'.join(body), encoding='utf-8')
    if state.get('candidate_hash'):
        (target / 'approval.txt').write_text(state['candidate_hash'] + '\n', encoding='utf-8')

def feature_task(state, feature):
    from verification_evidence import EXPECTED
    required = copy.deepcopy(EXPECTED)
    # Retain all earlier feature suites as regression requirements.
    for item in candidate(state)['features']:
        for source, folder in (('unit_suites', 'surefire-reports'), ('integration_suites', 'failsafe-reports')):
            for suite in item[source]:
                required[folder][suite['name']] = max(required[folder].get(suite['name'], 0), suite['minimum_tests'])
        if item['task_id'] == feature['task_id']: break
    return {'kind': 'planned_feature', 'task_id': feature['task_id'], 'title': feature['title'],
            'requirements': feature['requirements'], 'acceptance_criteria': feature['acceptance_criteria'],
            'excluded': feature['excluded'], 'depends_on': feature['depends_on'],
            'approved_design': candidate(state)['document'],
            'planning_origin': {'state_id': state['id'], 'revision': state['revision'], 'hash': plan_hash(state)},
            'verification_policy': {'runner': 'maven_verify', 'required_suites': required}}
