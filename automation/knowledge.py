"""Notion source snapshots and append-only, bounded artifact publication.

No raw CLI logs, credentials, environment or arbitrary files are published.
Records carry deterministic markers; retries reconcile remote state first.
"""
import argparse
import hashlib
import json
import os
import re
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
import controller as c
import delivery as d
import project_planning as p

_last_request = 0.0

def api(resource, identity, suffix='', method='GET', payload=None, query=None):
    global _last_request
    if resource not in ('blocks', 'pages') or suffix not in ('', '/children'):
        raise ValueError('Unapproved Notion resource')
    url = 'https://api.notion.com/v1/' + resource + '/' + str(uuid.UUID(identity)) + suffix
    if query: url += '?' + urllib.parse.urlencode(query)
    token = d.notion_token()
    wait = .4 - (time.monotonic() - _last_request)
    if wait > 0: time.sleep(wait)
    _last_request = time.monotonic()
    req = urllib.request.Request(url, method=method,
        data=None if payload is None else json.dumps(payload, ensure_ascii=False).encode(),
        headers={'Authorization': 'Bearer ' + token, 'Notion-Version': '2025-09-03',
                 'Content-Type': 'application/json'})
    with urllib.request.build_opener(d.NoRedirect()).open(req, timeout=10) as response:
        return json.load(response)

def children(page_id):
    result, cursor, seen = [], None, set()
    while True:
        query = {'page_size': 100}
        if cursor: query['start_cursor'] = cursor
        page = api('blocks', page_id, '/children', query=query)
        result.extend(page['results'])
        if len(result) > 5000: raise ValueError('Notion page exceeds supported block limit')
        if not page.get('has_more'): return result
        cursor = page.get('next_cursor')
        if not cursor or cursor in seen: raise ValueError('Invalid Notion pagination')
        seen.add(cursor)

def rich_text(items):
    values = []
    for item in items:
        value = item.get('plain_text')
        if value is None: value = item.get('text', {}).get('content')
        if value is None: value = json.dumps(item.get('mention', item.get('equation', {})), ensure_ascii=False)
        values.append(value)
    return ''.join(values)

def snapshot(page_id):
    before = api('pages', page_id)
    lines, count = [], 0
    def walk(identity, depth=0):
        nonlocal count
        if depth > 12: raise ValueError('Notion source nesting too deep')
        for block in children(identity):
            count += 1
            if count > 2000: raise ValueError('Notion source too large')
            kind = block['type']
            data = block.get(kind, {})
            if kind == 'child_page':
                lines.append('[Referenced child page, not fetched: ' + data.get('title', '') + ']')
                continue
            if kind in ('child_database', 'unsupported'):
                raise ValueError('Source contains unsupported database/block; select text source pages')
            if 'rich_text' in data: lines.append(rich_text(data['rich_text']))
            elif kind == 'table_row': lines.append(' | '.join(rich_text(v) for v in data['cells']))
            elif kind not in ('table', 'column', 'column_list', 'divider', 'table_of_contents'):
                raise ValueError('Unsupported source block type: ' + kind)
            if block.get('has_children'): walk(block['id'], depth + 1)
    walk(page_id)
    after = api('pages', page_id)
    if before['last_edited_time'] != after['last_edited_time']:
        raise ValueError('Source changed while being read; retry explicitly')
    text = '\n\n'.join(lines)
    if not text.strip() or len(text) > 120000: raise ValueError('Empty or oversized source snapshot')
    return {'page_id': str(uuid.UUID(page_id)), 'url': after['url'],
            'last_edited_time': after['last_edited_time'], 'text': text,
            'sha256': hashlib.sha256(text.encode()).hexdigest()}

def redact(text):
    # Defense in depth for generated prose. Raw logs/env are never selected.
    for key, value in os.environ.items():
        if value and len(value) >= 4 and any(t in key.upper() for t in ('TOKEN', 'PASSWORD', 'SECRET', 'API_KEY')):
            text = text.replace(value, '[REDACTED]')
    text = re.sub(r'(?i)\b(?:ntn_|secret_|sk-)[A-Za-z0-9_-]{8,}', '[REDACTED]', text)
    return re.sub(r'(?i)(bearer\s+)[A-Za-z0-9._-]+', r'\1[REDACTED]', text)

def record_payload(state):
    records = []
    for index, result in enumerate(state['results']):
        artifact = result.get('artifact', {})
        body = [f"{state['task'].get('task_id')} / revision {result['revision']} / {result['stage']}",
                'Mode: ' + state['mode'], 'Outcome: ' + result['outcome'], result['summary'],
                'Started: ' + result.get('started_at', 'not recorded'),
                'Finished: ' + result.get('finished_at', 'not recorded')]
        body += ['Evidence:'] + result['evidence']
        body += ['Review files:'] + result['review_files']
        body += ['Decision: ' + v for v in artifact.get('decisions', [])]
        body += ['Question: ' + v for v in artifact.get('questions', [])]
        body += ['Finding: ' + v for v in artifact.get('findings', [])]
        if artifact.get('document'): body += [artifact['document']]
        if artifact.get('features'):
            body += ['Feature handoff definitions:', json.dumps(artifact['features'], ensure_ascii=False, indent=2)]
        category = 'plans' if result['stage'] == 'plan' else 'activity'
        records.append((category, f"{state['id']}:{index}", '\n\n'.join(body)))
    # Track meaningful human intervention and outcomes without arbitrary errors/logs.
    for index, event in enumerate(state.get('events', [])):
        if event['kind'] in ('idea', 'revision', 'approval', 'handoff', 'human_accept'):
            records.append(('activity', f"{state['id']}:event:{index}",
                            event['at'] + '\n' + event['kind'] + '\n' + event['message']))
    for path in sorted((c.ROOT / 'prompts').glob('*.md')):
        records.append(('prompts', path.name, path.read_text(encoding='utf-8')))
    guide = c.ROOT / 'PROJECT-AUTOMATION.md'
    if guide.exists(): records.append(('workflow', 'operating-guide', guide.read_text(encoding='utf-8')))
    # Obtain actual implementation/review instructions from the runtime prompt builder.
    for stage in ('implement', 'review', 'final_test', 'manager'):
        dummy = c.new_state({'title': 'ROLE TEMPLATE', 'task_id': 'TEMPLATE'}, 'mock')
        dummy['id'] = 'ROLE-TEMPLATE'
        dummy['stage'] = stage
        records.append(('prompts', 'feature-' + stage, c.prompt_for(dummy)))
    return records

def append_record(page_id, key, body):
    """Single-block parts: each marker and content are written together.

    Reconciliation prevents normal duplicate retries, but the API offers no
    transactional exactly-once contract across client crashes/visibility delays.
    """
    safe = redact(body)
    record_id = p.digest({'key': key, 'body': safe})
    parts = [safe[n:n+16000] for n in range(0, len(safe), 16000)] or ['']
    remote = [rich_text(b.get(b['type'], {}).get('rich_text', [])) for b in children(page_id)]
    for index, part in enumerate(parts):
        marker = f'[HJ-RECORD {record_id} {index+1}/{len(parts)}]'
        content = marker + '\n' + part
        matched = [v for v in remote if v.startswith(marker)]
        if matched:
            if any(v != content for v in matched):
                raise ValueError('Published record was edited; preserve human edits and stop')
            continue
        rich = [{'type': 'text', 'text': {'content': content[n:n+1800]}}
                for n in range(0, len(content), 1800)]
        api('blocks', page_id, '/children', 'PATCH', {'children': [
            {'object': 'block', 'type': 'paragraph', 'paragraph': {'rich_text': rich}}]})
    return record_id

def flush(store, settings=None):
    state = c.read(store.path)
    if state['mode'] != 'live': return []
    settings = settings or c.read(c.ROOT / 'integrations.json')
    pages = settings.get('notion', {}).get('knowledge_pages', {})
    outcomes = []
    with c.lock(c.ROOT / '.runtime-delivery/knowledge.lock', timeout=0):
        for category, key, body in record_payload(state):
            if category not in pages:
                outcomes.append(category + ': setup_required'); continue
            identity = p.digest({'page': pages[category], 'key': key, 'body': redact(body)})
            receipt = c.read(store.path).get('knowledge_receipts', {}).get(identity, {})
            if receipt.get('status') in ('sent', 'needs_user'): continue
            try:
                append_record(pages[category], key, body)
            except d.NotConfigured:
                outcomes.append(category + ': setup_required'); break
            except Exception:
                count = receipt.get('attempts', 0) + 1
                receipt = {'status': 'needs_user' if count >= 3 else 'pending', 'attempts': count,
                           'category': category, 'last_error': 'Publication failed; inspect permissions/network'}
                outcomes.append(category + ': ' + receipt['status'])
            else:
                receipt = {'status': 'sent', 'attempts': receipt.get('attempts', 0) + 1, 'category': category}
                outcomes.append(category + ': sent')
            store.update(lambda s: s.setdefault('knowledge_receipts', {}).update({identity: receipt}))
            if receipt['status'] != 'sent': break  # One failure per flush; no storm.
    return outcomes

def best_effort(store):
    try:
        outcomes = flush(store)
        for outcome in sorted(set(outcomes)): print('[knowledge] ' + outcome, flush=True)
        if any('pending' in v or 'needs_user' in v or 'setup_required' in v for v in outcomes):
            state = c.read(store.path)
            issue = p.digest(outcomes)
            if state.get('knowledge_notice') != issue:
                settings = c.read(c.ROOT / 'integrations.json')
                d.send_notification({'snapshot': {'task_id': state['task'].get('task_id', 'Planning'),
                                                  'stage': 'needs_user'}}, settings)
                store.update(lambda s: s.update(knowledge_notice=issue))
    except Exception:
        print('[knowledge] deferred; local artifacts retained. No agent replay.', flush=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['flush', 'status', 'retry'])
    parser.add_argument('--state-dir', type=Path, default=c.ROOT / '.runtime-project')
    parser.add_argument('--activate', action='store_true')
    parser.add_argument('--reason')
    args = parser.parse_args()
    store = c.Store(args.state_dir.resolve())
    if args.command == 'status':
        print(json.dumps(c.read(store.path).get('knowledge_receipts', {}), indent=2))
    elif args.command == 'retry':
        if not args.reason: raise ValueError('Retry reason required')
        def retry(s):
            for receipt in s.get('knowledge_receipts', {}).values():
                if receipt['status'] == 'needs_user':
                    receipt.setdefault('history', []).append({'attempts': receipt['attempts'], 'reason': args.reason})
                    receipt.update(status='pending', attempts=0)
        store.update(retry)
    else:
        if not args.activate: raise ValueError('--activate required')
        outcomes = flush(store)
        print('\n'.join(outcomes))
        if any('setup_required' in v for v in outcomes) or any(v['status'] != 'sent' for v in c.read(store.path).get('knowledge_receipts', {}).values()):
            raise ValueError('Some records remain undelivered')

if __name__ == '__main__':
    try: main()
    except Exception:
        print('Knowledge command stopped. Check local state and Notion content permissions.')
        raise SystemExit(1)
