"""Durable, transport-independent workflow events stored with task state.

No network, notifications, or agent calls occur in this module. Delivery receipts
must be updated under the controller's state lock by a separate dispatcher.
"""
import hashlib
import json

STAGES = {'plan', 'spec_review', 'awaiting_plan_approval', 'implement', 'review',
          'final_test', 'pr_ready', 'manager', 'needs_user'}
WAITING = {'awaiting_plan_approval', 'pr_ready', 'needs_user'}

def snapshot(state):
    # Do not export agent prose, errors, environment variables, or tool logs.
    stage = state.get('stage')
    if state.get('mode') != 'live' or stage not in STAGES:
        return None
    task_id = state.get('task', {}).get('task_id')
    if not isinstance(task_id, str) or not task_id:
        return None
    events = state.get('events', [])
    last_kind = events[-1].get('kind') if events else None
    if last_kind not in {'activation', 'attention', 'breaker', 'breaker_reset',
                         'pause', 'revision', 'repair', 'approval', 'recheck', 'idea'}:
        last_kind = None
    results = state.get('results', [])
    outcome = results[-1].get('outcome') if results else None
    if outcome not in ('pass', 'changes', 'blocked'):
        outcome = None
    return dict(state_id=state['id'], task_id=task_id, revision=state['revision'],
                stage=stage, active=bool(state['active']), running=bool(state['running']),
                repairs=state['repairs'], manager_used=bool(state['manager_used']),
                breaker=bool(state.get('run_guard', {}).get('latched')),
                event_count=len(events), last_kind=last_kind,
                result_count=len(results), outcome=outcome)

def enqueue(state, at):
    view = snapshot(state)
    if view is None:
        return
    digest = hashlib.sha256(json.dumps(view, sort_keys=True).encode()).hexdigest()
    if state.get('outbox_snapshot') == digest:
        return
    state['outbox_snapshot'] = digest
    notify = view['last_kind'] != 'idea' and not view['running'] and not view['active'] and (
        view['stage'] in WAITING or view['last_kind'] in ('attention', 'breaker'))
    entry = dict(id=digest, created_at=at, snapshot=view, channels={})
    for channel in ('notion', 'notification'):
        if channel == 'notification' and not notify:
            continue
        entry['channels'][channel] = dict(status='pending', attempts=0, last_error=None)
    state.setdefault('outbox', []).append(entry)

def pending(state, channel):
    if channel not in ('notion', 'notification'):
        raise ValueError('Unknown delivery channel')
    return [item for item in state.get('outbox', [])
            if item['channels'].get(channel, {}).get('status') == 'pending']

def receipt(state, event_id, channel, succeeded, at):
    item = next((e for e in state.get('outbox', []) if e['id'] == event_id), None)
    if item is None or channel not in item['channels']:
        raise ValueError('Unknown outbox event/channel')
    delivery = item['channels'][channel]
    if delivery['status'] != 'pending':
        return  # Duplicate acknowledgement does not increment attempts.
    delivery['attempts'] += 1
    delivery['last_attempt_at'] = at
    if succeeded:
        delivery.update(status='sent', last_error=None)
    else:
        # Do not copy remote exceptions, which could contain credentials.
        delivery['last_error'] = 'Delivery failed; inspect the local adapter.'
        if delivery['attempts'] >= 3:
            delivery['status'] = 'needs_user'

def retry(state, event_id, channel, reason):
    if not reason or not reason.strip():
        raise ValueError('A retry reason is required')
    item = next((e for e in state.get('outbox', []) if e['id'] == event_id), None)
    if item is None or channel not in item['channels']:
        raise ValueError('Unknown outbox event/channel')
    delivery = item['channels'][channel]
    if delivery['status'] != 'needs_user':
        raise ValueError('Only a failed delivery can be retried explicitly')
    delivery.setdefault('history', []).append(dict(attempts=delivery['attempts'], reason=reason.strip()))
    delivery.update(status='pending', attempts=0, last_error=None)
