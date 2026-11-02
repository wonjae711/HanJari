"""Project plans projected into existing Notion documents, without model calls.

Each version is an immutable managed toggle. Original/user blocks stay intact.
Generated toggles are excluded from source snapshots to avoid feedback loops.
"""
import re
import uuid
import controller as c
import project_planning as p
import knowledge as k

MARKER = re.compile(r'^\[HJ-DOC/v1 ([a-z_]+) ([0-9a-f]{64})\] ')
ROLES = {'overview', 'decisions', 'requirements', 'sequence', 'architecture', 'erd', 'api'}
RULES = {
    'overview': ('목표', '범위', '확정', '사용자 흐름', '화면', '미정'),
    'requirements': ('범위', '확정', '사용자 흐름', '인증', '상태', '경합', '멱등', '운영', '화면', '검증', '인수', '미정'),
    'architecture': ('기술', '상태', '정합성', '경합', '멱등', '복구', '운영'),
    'erd': ('데이터 모델', 'ERD'),
    'api': ('API',),
}

def is_managed(block):
    if block.get('type') != 'toggle': return False
    match = MARKER.match(k.rich_text(block.get('toggle', {}).get('rich_text', [])))
    return bool(match and match[1] in ROLES)

def sections(document):
    result, current = [], []
    for line in document.splitlines():
        if line.startswith('## '):
            if current: result.append('\n'.join(current).strip())
            current = [line]
        elif current: current.append(line)
    if current: result.append('\n'.join(current).strip())
    return result

def feature_text(features):
    chunks = []
    for feature in features:
        chunks += [f"## {feature['task_id']} — {feature['title']}", feature['requirements'],
                   '의존 업무: ' + (', '.join(feature['depends_on']) or '없음'),
                   '인수 기준:\n' + '\n'.join('- ' + x for x in feature['acceptance_criteria']),
                   '제외 범위:\n' + '\n'.join('- ' + x for x in feature['excluded'])]
    return '\n\n'.join(chunks)

def documents(state):
    if not p.is_project(state): return {}
    current = [(i, r) for i, r in enumerate(state['results']) if r['revision'] == state['revision']]
    plans = [(i, r) for i, r in current if r['stage'] == 'plan' and r.get('artifact', {}).get('document')]
    if not plans: return {}
    index, draft = plans[-1]
    artifact = draft['artifact']
    reviews = [r for i, r in current if i > index and r['stage'] == 'spec_review']
    reviewed = bool(reviews and reviews[-1]['outcome'] == 'pass')
    approved = (draft['outcome'] == 'pass' and state.get('approved_plan', {}).get('hash') == p.plan_hash(state))
    label = '사용자 기획 승인됨 · 상세 문서 완성/구현 완료와는 별개' if approved else (
        '독립 검토 통과 · 사용자 전체 기획 승인 대기' if reviewed else '기획 초안 · 검토/사용자 결정 필요')
    header = f"요구사항 버전 r{state['revision']} / 기획 산출물 #{index+1}\n{label}\n"
    header += '이 영역은 자동 반영본이다. 원래 문서는 보존된다. 수정 지시는 영역 밖 또는 workspace.py revise로 전달한다.\n'
    header += '문서 상태는 승인 전 초안, 기획 승인 후 작성 중으로 표시한다. 구현·실험 결과나 문서 완성을 자동 선언하지 않는다.\n'
    text_sections = sections(artifact['document'])
    result = {}
    for role, words in RULES.items():
        selected = [s for s in text_sections if any(w.casefold() in s.splitlines()[0].casefold() for w in words)]
        if selected: result[role] = header + '\n\n'.join(selected)
    features = artifact.get('features', [])
    if features:
        result['sequence'] = header + '의존 순서에 따른 업무 목록이다. 난이도·소요 시간은 별도 평가 전이며 임의 추정하지 않는다.\n\n' + feature_text(features)
        result['requirements'] = result.get('requirements', header) + '\n\n# 기능별 인수 기준\n\n' + feature_text(features)
    decisions = ['## 사용자 확정 제약'] + state['task'].get('confirmed_constraints', [])
    decisions += ['## 사용자가 전달한 변경 지시'] + state['task'].get('steering', [])
    decisions += ['## AI의 반영 판단과 제안 — 사용자 결정과 구분'] + artifact.get('decisions', [])
    decisions += ['## 남은 질문'] + artifact.get('questions', [])
    if reviews: decisions += ['## 최신 독립 검토', reviews[-1]['summary']] + reviews[-1].get('artifact', {}).get('findings', [])
    decisions += ['## 전체 기획 승인', state.get('approved_plan', {}).get('hash', '미승인')]
    result['decisions'] = header + '\n\n'.join(decisions)
    return {role: {'body': k.redact(body), 'status': '작성 중' if approved else '초안',
                   'version': f"r{state['revision']} / #{index+1}", 'label': label} for role, body in result.items()}

def rich(text):
    return [{'type': 'text', 'text': {'content': text[n:n+1800]}} for n in range(0, len(text), 1800)]

def blocks(body):
    result = []
    for part in body.split('\n\n'):
        if not part.strip(): continue
        heading = re.fullmatch(r'#{1,3} (.+)', part)
        kind = 'heading_3' if heading else 'paragraph'
        text = heading[1] if heading else part
        for n in range(0, len(text), 14000):
            result.append({'object': 'block', 'type': kind, kind: {'rich_text': rich(text[n:n+14000])}})
    if len(result) > 90:
        result = [{'object': 'block', 'type': 'paragraph', 'paragraph': {'rich_text': rich(body[n:n+14000])}}
                  for n in range(0, len(body), 14000)]
    if len(result) > 90: raise ValueError('Document exceeds supported publication size')
    return result

def signature(items):
    return [(b['type'], k.rich_text(b.get(b['type'], {}).get('rich_text', [])), bool(b.get('has_children')))
            for b in items]

def validate_page(page, target, settings):
    if page.get('archived') or page.get('in_trash'): raise ValueError('Project document is archived')
    parent = page.get('parent', {})
    if parent.get('data_source_id') != settings['data_source_id']:
        raise ValueError('Project document belongs to a different data source')
    props = page['properties']
    if k.rich_text(props[settings['title_property']]['title']) != target['title']:
        raise ValueError('Project document title mismatch')
    status = props[settings['status_property']]
    if status['type'] != 'select': raise ValueError('Unexpected status property type')
    return (status.get('select') or {}).get('name')

def publish(role, payload, target, settings, state_id):
    page_id = str(uuid.UUID(target['page_id']))
    current = validate_page(k.api('pages', page_id), target, settings)
    record_id = p.digest({'state': state_id, 'role': role, **payload})
    marker = f'[HJ-DOC/v1 {role} {record_id}] '
    title = marker + payload['version'] + ' — ' + payload['label']
    expected = blocks(payload['body'])
    roots = k.children(page_id)
    matches = [b for b in roots if is_managed(b) and k.rich_text(b['toggle']['rich_text']).startswith(marker)]
    if len(matches) > 1: raise ValueError('Duplicate document projections need manual inspection')
    if not matches:
        if current == '완료': raise ValueError('Human-completed document is preserved; reopen it explicitly before publishing')
        k.api('blocks', page_id, '/children', 'PATCH', {'children': [{
            'object': 'block', 'type': 'toggle', 'toggle': {'rich_text': rich(title), 'children': expected}}]})
        matches = [b for b in k.children(page_id) if is_managed(b) and k.rich_text(b['toggle']['rich_text']).startswith(marker)]
    if len(matches) != 1: raise ValueError('Publication not confirmed; retry only after inspecting remote state')
    if k.rich_text(matches[0]['toggle']['rich_text']) != title or signature(k.children(matches[0]['id'])) != signature(expected):
        raise ValueError('Published projection was edited or incomplete; preserve edits and stop')
    # Re-read properties before changing only the document status, not other fields.
    current = validate_page(k.api('pages', page_id), target, settings)
    if current != '완료' and current != payload['status']:
        k.api('pages', page_id, method='PATCH', payload={'properties': {
            settings['status_property']: {'select': {'name': payload['status']}}}})
        after = validate_page(k.api('pages', page_id), target, settings)
        if after != payload['status']: raise ValueError('Document status update not confirmed')
    return record_id

def flush(store, settings):
    state = c.read(store.path)
    if state['mode'] != 'live' or not p.is_project(state): return []
    mapping = settings.get('notion', {}).get('project_documents')
    if not mapping: return []
    payloads = documents(state)
    outcomes = []
    for role, payload in payloads.items():
        if role not in mapping['pages']:
            outcomes.append('document ' + role + ': setup_required'); continue
        target = mapping['pages'][role]
        key = p.digest({'state': state['id'], 'page': target['page_id'], 'role': role, **payload})
        receipt = c.read(store.path).get('document_receipts', {}).get(key, {})
        if receipt.get('status') in ('sent', 'needs_user'): continue
        try:
            publish(role, payload, target, mapping, state['id'])
        except Exception:
            attempts = receipt.get('attempts', 0) + 1
            receipt = {'status': 'needs_user' if attempts >= 3 else 'pending', 'attempts': attempts,
                       'role': role, 'last_error': 'Document publication failed; check mapping, permissions and human edits'}
        else:
            receipt = {'status': 'sent', 'attempts': receipt.get('attempts', 0)+1, 'role': role}
        store.update(lambda s: s.setdefault('document_receipts', {}).update({key: receipt}))
        outcomes.append('document ' + role + ': ' + receipt['status'])
        if receipt['status'] != 'sent': break
    return outcomes
