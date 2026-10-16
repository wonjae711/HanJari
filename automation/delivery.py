"""Explicitly activated Windows notifications and Notion state projection.

This module never starts/resumes agents. Transports are independent of workflow
success. State changes and messages remain in the outbox until acknowledged.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import urllib.request
import urllib.error
import controller as c
import outbox

ROOT = Path(__file__).resolve().parent
STATUS = {'plan': '기획 대기', 'spec_review': '기획 검토',
          'awaiting_plan_approval': '기획 검토', 'implement': '개발 중',
          'review': '검증 중', 'final_test': '검증 중', 'pr_ready': '사용자 리뷰 대기',
          'manager': '보류', 'needs_user': '보류'}
OWNER = {'plan': 'GPT 기획', 'spec_review': 'GPT 리뷰',
         'awaiting_plan_approval': '사용자', 'implement': 'Claude 개발',
         'review': 'GPT 리뷰', 'final_test': 'GPT 리뷰', 'pr_ready': '사용자',
         'manager': 'GPT 관리', 'needs_user': '사용자'}

class NotConfigured(Exception):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('Notion redirect rejected')

def notion_request(page_id, token, method='GET', payload=None):
    # No configurable host or redirects: credentials go only to api.notion.com.
    page_id = str(uuid.UUID(page_id))
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request('https://api.notion.com/v1/pages/' + page_id,
        data=data, method=method, headers={'Authorization': 'Bearer ' + token,
        'Notion-Version': '2025-09-03', 'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(NoRedirect())
    with opener.open(req, timeout=5) as response:
        return json.load(response)

def notion_properties(view):
    stopped = view['breaker'] or (not view['active'] and not view['running'] and
              view['last_kind'] in ('attention', 'breaker', 'pause') and view['stage'] not in outbox.WAITING)
    return {
        '진행 상태': {'select': {'name': '보류' if stopped else STATUS[view['stage']]}},
        '담당': {'select': {'name': '사용자' if stopped else OWNER[view['stage']]}},
        '수정 횟수': {'number': view['repairs']},
        '관리 재계획': {'checkbox': view['manager_used']},
    }

def notion_token():
    token = os.environ.get('HANJARI_NOTION_TOKEN')
    if token:
        return token
    if os.name != 'nt' or not (ROOT / '.runtime-delivery/notion-token.dpapi').exists():
        raise NotConfigured('Notion token is not configured')
    try:
        token = unprotect_token(ROOT / '.runtime-delivery/notion-token.dpapi')
    except Exception:
        raise NotConfigured('Cannot decrypt token for this Windows user') from None
    if not token:
        raise NotConfigured('Empty token')
    return token

def unprotect_token(path):
    """Read ConvertFrom-SecureString's Windows DPAPI hex format directly.

    Avoid child PowerShell module initialization; DPAPI still enforces the same
    Windows user/machine boundary. No token is written to subprocess stdout.
    """
    import ctypes
    from ctypes import wintypes
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.c_void_p)]
    encrypted = bytes.fromhex(path.read_text(encoding='ascii').strip())
    buffer = ctypes.create_string_buffer(encrypted)
    source = Blob(len(encrypted), ctypes.cast(buffer, ctypes.c_void_p))
    target = Blob()
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    crypt.CryptUnprotectData.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    crypt.CryptUnprotectData.restype = wintypes.BOOL
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if not crypt.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise OSError('Windows DPAPI decryption failed')
    try:
        return ctypes.string_at(target.data, target.size).decode('utf-16-le').strip()
    finally:
        ctypes.memset(target.data, 0, target.size)
        kernel.LocalFree(target.data)

def send_notion(item, integrations):
    settings = integrations.get('notion', {})
    page_id = settings.get('tasks', {}).get(item['snapshot']['task_id'])
    if settings.get('sync_mode') != 'direct-api':
        raise NotConfigured('Notion token or task mapping missing')
    if not page_id:
        journal = settings.get('knowledge_pages', {}).get('activity')
        if not journal: raise NotConfigured('Notion task mapping or activity journal missing')
        from knowledge import append_record
        append_record(journal, item['id'], 'Workflow status\n' + json.dumps(item['snapshot'], ensure_ascii=False, indent=2))
        return
    token = notion_token()
    current = notion_request(page_id, token)
    props = current['properties']
    view = item['snapshot']
    current_id = ''.join(t.get('plain_text', '') for t in props['업무 ID']['rich_text'])
    if current_id != view['task_id'] or props['요구사항 버전']['number'] != view['revision']:
        raise ValueError('Notion task/revision mismatch; user review required')
    if (props['진행 상태'].get('select') or {}).get('name') == '완료':
        raise ValueError('Human-completed Notion task must not be reopened')
    expected = notion_properties(view)
    for name, data in expected.items():
        if props[name]['type'] != next(iter(data)):
            raise ValueError('Notion property type mismatch')
    updated = notion_request(page_id, token, 'PATCH', {'properties': expected})
    if uuid.UUID(updated['id']) != uuid.UUID(page_id):
        raise ValueError('Unexpected Notion response')

def send_notification(item, integrations):
    if integrations.get('notifications', {}).get('provider') != 'windows':
        raise NotConfigured('Windows notification provider not enabled')
    if os.name != 'nt':
        raise NotConfigured('Windows required')
    view = item['snapshot']
    if view['stage'] == 'pr_ready':
        message = 'PR 준비 완료. 사용자 코드 리뷰와 최종 판단이 필요합니다.'
    elif view['stage'] == 'awaiting_plan_approval':
        message = '기획 검토 완료. 사용자 승인이 필요합니다.'
    else:
        message = '작업이 중단되었거나 사용자 판단이 필요합니다. 상태와 로그를 확인하세요.'
    env = {k: v for k, v in os.environ.items() if not k.startswith('HANJARI_')}
    env['HANJARI_NOTICE_TITLE'] = integrations.get('project_title', 'HanJari') + ' - ' + view['task_id'][:50]
    env['HANJARI_NOTICE_TAG'] = __import__('hashlib').sha256(integrations.get('project_id', 'hanjari').encode()).hexdigest()[:16]
    env['HANJARI_NOTICE_TEXT'] = message
    # Fixed script path; text is passed as data, never interpolated into commands.
    script = ROOT / 'notify-windows.ps1'
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive',
                             '-WindowStyle', 'Hidden', '-File', str(script)],
                            env=env, capture_output=True, timeout=20,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError('Windows notification adapter failed')
    # Success means submitted to Windows, not proof the user saw the banner.

def flush(store, integrations, delivery_root=None, senders=None):
    delivery_root = Path(delivery_root) if delivery_root else ROOT / '.runtime-delivery'
    senders = senders or {'notion': send_notion, 'notification': send_notification}
    outcomes = []
    with c.lock(delivery_root / 'delivery.lock', timeout=0):
        ledger_path = delivery_root / 'ledger.json'
        ledger = c.read(ledger_path) if ledger_path.exists() else {}
        for channel in ('notification', 'notion'):
            state = c.read(store.path)
            entries = [e for e in state.get('outbox', []) if channel in e['channels']]
            if not entries:
                continue
            latest = entries[-1]
            if channel == 'notification' and (state['active'] or state['running']):
                def recovered(s):
                    for entry in s.get('outbox', []):
                        rec = entry['channels'].get(channel)
                        if rec and rec['status'] in ('pending', 'needs_user'):
                            rec['status'] = 'superseded'
                store.update(recovered)
                continue
            # Coalesce old projections, including old failures, to the newest state.
            def supersede(s):
                for entry in s.get('outbox', []):
                    rec = entry['channels'].get(channel)
                    if rec and entry['id'] != latest['id'] and rec['status'] in ('pending', 'needs_user'):
                        rec['status'] = 'superseded'
            store.update(supersede)
            if latest['channels'][channel]['status'] != 'pending':
                continue
            key = latest['snapshot']['task_id'] + ':' + channel
            old = ledger.get(key)
            if old and (old['at'] > latest['created_at'] or old['id'] == latest['id']):
                def stale(s):
                    next(e for e in s['outbox'] if e['id'] == latest['id'])['channels'][channel]['status'] = 'superseded'
                store.update(stale)
                continue
            try:
                senders[channel](latest, integrations)
            except NotConfigured:
                outcomes.append(channel + ': setup_required')
                continue  # Missing configuration consumes no retry attempts.
            except Exception as exc:
                store.update(lambda s: outbox.receipt(s, latest['id'], channel, False, c.now()))
                code = ' HTTP ' + str(exc.code) if isinstance(exc, urllib.error.HTTPError) else ''
                outcomes.append(channel + ': failed' + code)
            else:
                ledger[key] = {'at': latest['created_at'], 'id': latest['id']}
                c.save(ledger_path, ledger)
                store.update(lambda s: outbox.receipt(s, latest['id'], channel, True, c.now()))
                outcomes.append(channel + ': submitted')
    return outcomes

def best_effort(store, config):
    if not config.get('integration_delivery'):
        return
    try:
        integrations = config.get('integrations') or c.read(ROOT / 'integrations.json')
        for outcome in flush(store, integrations, config.get('delivery_root')):
            print('[delivery] ' + outcome, flush=True)
    except Exception:
        print('[delivery] deferred; inspect outbox and delivery lock. Workflow result preserved.', flush=True)

def main():
    p = argparse.ArgumentParser()
    p.add_argument('command', choices=['status', 'flush', 'retry', 'test-notification'])
    p.add_argument('--state-dir', type=Path, default=ROOT / '.runtime-pilot')
    p.add_argument('--activate', action='store_true')
    p.add_argument('--event-id')
    p.add_argument('--channel', choices=['notion', 'notification'])
    p.add_argument('--reason')
    args = p.parse_args()
    settings = c.read(ROOT / 'integrations.json')
    if args.command == 'test-notification':
        if not args.activate: p.error('--activate required')
        send_notification({'snapshot': {'task_id': '알림 연결 시험', 'stage': 'awaiting_plan_approval'}}, settings)
        print('Submitted to Windows. Confirm the visible banner separately.')
        return
    store = c.Store(args.state_dir.resolve())
    if args.command == 'status':
        s = c.read(store.path)
        print(json.dumps([{'id': e['id'], 'stage': e['snapshot']['stage'], 'channels': e['channels']}
                          for e in s.get('outbox', [])], ensure_ascii=False, indent=2))
    elif args.command == 'retry':
        store.update(lambda s: outbox.retry(s, args.event_id, args.channel, args.reason))
        print('Retry enabled. No workflow activation or delivery performed.')
    else:
        if not args.activate: p.error('--activate required')
        store.update(lambda s: None)  # Capture older completed states once.
        outcomes = flush(store, settings)
        for outcome in outcomes: print(outcome)
        s = c.read(store.path)
        if any(e['channels'][ch]['status'] in ('pending', 'needs_user')
               for e in s.get('outbox', []) for ch in e['channels']):
            raise RuntimeError('Delivery pending; inspect status. Agents were not restarted.')

if __name__ == '__main__':
    try: main()
    except Exception:
        # Never print remote exception bodies or credential-bearing request objects.
        print('Delivery stopped. Check configuration, token access, and local delivery status.', file=sys.stderr)
        sys.exit(1)
