"""Persistent attempt limits. No automatic reset on resume or process restart."""
DEFAULTS = {'max_attempts': 20, 'max_stage_attempts': 6, 'max_consecutive_failures': 3}

def limits(config):
    values = {**DEFAULTS, **config.get('run_limits', {})}
    if set(values) != set(DEFAULTS) or any(type(v) is not int or v <= 0 for v in values.values()):
        raise ValueError('run_limits must contain positive integer limits only')
    return values

def state_guard(state):
    return state.setdefault('run_guard', {'attempts': 0, 'stage_attempts': {},
                                         'consecutive_failures': {}, 'latched': False, 'reason': None})

def trip(state, reason, event):
    guard = state_guard(state)
    guard['latched'], guard['reason'] = True, reason
    state['active'] = False
    event(state, 'breaker', reason)

def claim(state, config, event):
    guard, policy = state_guard(state), limits(config)
    key = f"{state['revision']}:{state['stage']}"
    if guard['latched']:
        state['active'] = False
        return False
    if guard['attempts'] >= policy['max_attempts']:
        trip(state, 'Task attempt limit reached; explicit reset with a reason is required.', event)
        return False
    if guard['stage_attempts'].get(key, 0) >= policy['max_stage_attempts']:
        trip(state, 'Stage attempt limit reached; explicit reset with a reason is required.', event)
        return False
    guard['attempts'] += 1
    guard['stage_attempts'][key] = guard['stage_attempts'].get(key, 0) + 1
    return True

def record(state, selected, outcome, config, event):
    guard, policy = state_guard(state), limits(config)
    key = f"{selected['revision']}:{selected['stage']}"
    failures = guard['consecutive_failures']
    failures[key] = failures.get(key, 0) + 1 if outcome in ('blocked', 'error') else 0
    if failures[key] >= policy['max_consecutive_failures']:
        trip(state, f'{key}: consecutive failures reached {failures[key]}; inspect logs before resetting.', event)

def reset(state, reason, event):
    if not reason or not reason.strip():
        raise ValueError('reset-breaker requires --text explaining the fix')
    if state['running'] or state['active']:
        raise ValueError('Cannot reset an active/running task')
    old = state_guard(state)
    if not old['latched']:
        raise ValueError('The breaker has not tripped')
    state.setdefault('run_guard_history', []).append({'guard': old, 'reason': reason.strip()})
    state.pop('run_guard')
    state_guard(state)
    event(state, 'breaker_reset', reason.strip())
