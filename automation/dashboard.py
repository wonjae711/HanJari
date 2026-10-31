"""Loopback-only GUI for the existing workspace CLI; no agents on page load."""
import argparse
import json
import math
import re
import secrets
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
import controller as c
import workspace as w
import knowledge

ROOT = Path(__file__).resolve().parent
ACTIONS = {'run', 'pause', 'note', 'revise', 'approve', 'sync', 'accept-feature', 'retry-sync', 'reset-breaker'}
TEXT_ACTIONS = {'note', 'revise', 'accept-feature', 'retry-sync', 'reset-breaker'}


class Backend:
    def __init__(self, config_path, mode='live'):
        self.config_path = Path(config_path).resolve()
        self.mode = mode
        self.jobs = []
        self.mutex = threading.Lock()

    def profile(self, key):
        if not isinstance(key, str) or not re.fullmatch(r'[a-z][a-z0-9-]{1,39}', key):
            raise ValueError('등록된 프로젝트를 선택하세요.')
        shared = c.read(self.config_path)
        profile = w.load_profile(key, shared)
        directory = Path(profile['state_dir'])
        if self.mode == 'mock': directory = directory.with_name(directory.name + '-mock')
        return profile, c.Store(directory), shared

    def projects(self):
        keys = ['hanjari'] + sorted(p.parent.name for p in w.REGISTRY.glob('*/profile.json'))
        result = []
        for key in keys:
            try:
                profile, _, _ = self.profile(key)
                result.append({'id': key, 'title': profile['title']})
            except (OSError, ValueError, KeyError):
                if key == 'hanjari': raise ValueError('automation/config.json 및 프로젝트 연결 설정을 확인하세요.')
        return result

    def usage(self, shared):
        try:
            path = Path(shared['usage_file'])
            raw = c.read(path)
            age = time.time() - path.stat().st_mtime
            values = {name: raw[name]['used_percentage'] for name in ('five_hour', 'seven_day')}
            if any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 100 for v in values.values()):
                raise ValueError()
            return dict(values, age_seconds=max(0, round(age)), fresh=-30 <= age <= shared.get('usage_max_age_seconds', 300))
        except (OSError, ValueError, KeyError, TypeError): return None

    def snapshot(self, key):
        profile, store, shared = self.profile(key)
        data = {'project': {'id': key, 'title': profile['title']}, 'mode': self.mode, 'usage': self.usage(shared)}
        with self.mutex:
            data['jobs'] = [dict(j) for j in self.jobs if j['project'] == key][-12:]
            data['busy'] = any(j['status'] == 'running' for j in self.jobs)
        if not store.path.exists(): return dict(data, stage='not_started', results=[], events=[], features=[], document='')
        parent = c.read(store.path)
        current = c.read(w.current_store(store).path)
        plans = [r for r in parent['results'] if r['stage'] == 'plan' and r['revision'] == parent['revision']]
        artifact = plans[-1].get('artifact', {}) if plans else {}
        approved = parent.get('approved_plan')
        data.update({k: current.get(k) for k in ('stage', 'active', 'running', 'revision', 'run_guard')})
        data.update(parent_stage=parent['stage'], parent_revision=parent['revision'],
                    pause_requested=parent.get('pause_requested', False), approved=bool(approved),
                    candidate_hash=parent.get('candidate_hash'), document=artifact.get('document', ''),
                    features=artifact.get('features', []), accepted=list(parent.get('accepted_features', {})),
                    current_feature=current['task'].get('task_id') if current['id'] != parent['id'] else None,
                    events=(parent.get('events', []) + (current.get('events', []) if current['id'] != parent['id'] else []))[-25:],
                    results=[{k: r.get(k) for k in ('stage', 'revision', 'outcome', 'summary', 'evidence', 'review_files', 'artifact')}
                             for r in current.get('results', [])[-12:]],
                    questions=artifact.get('questions', []),
                    receipts=[{'kind': kind, 'status': r['status']} for kind in ('knowledge_receipts', 'document_receipts')
                              for r in parent.get(kind, {}).values() if r['status'] != 'sent'])
        # No config, environment, token or arbitrary filesystem access is exposed.
        return json.loads(knowledge.redact(json.dumps(data, ensure_ascii=False)))

    def command(self, payload):
        if not isinstance(payload, dict): raise ValueError('잘못된 요청입니다.')
        action, key = payload.get('action'), payload.get('project')
        if action not in ACTIONS: raise ValueError('지원하지 않는 명령입니다.')
        _, store, _ = self.profile(key)
        args = [sys.executable, '-u', str(ROOT/'workspace.py'), action, '--project', key,
                '--config', str(self.config_path), '--mode', self.mode]
        if action in ('run', 'sync'): args += ['--activate']
        if action in TEXT_ACTIONS:
            text = payload.get('text')
            if not isinstance(text, str) or not text.strip() or len(text) > 12000:
                raise ValueError('내용을 1~12,000자로 입력하세요.')
            args += ['--text', text.strip()]
        if action == 'approve':
            value = payload.get('hash')
            state = c.read(store.path)
            if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value) or value != state.get('candidate_hash'):
                raise ValueError('기획 버전이 바뀌었습니다. 새로고침 후 다시 검토하세요.')
            args += ['--hash', value]
        if action == 'accept-feature':
            state = c.read(store.path)
            feature = w.pending_feature(state) if state.get('approved_plan') else None
            if not feature or payload.get('feature') != feature['task_id']:
                raise ValueError('현재 리뷰 대상 기능을 다시 확인하세요.')
            args += ['--feature', feature['task_id']]
        return action, key, args

    def submit(self, payload):
        action, key, args = self.command(payload)
        with self.mutex:
            running = [j for j in self.jobs if j['status'] == 'running']
            if running and (action not in ('pause', 'note') or any(j['action'] in ('pause', 'note') for j in running)):
                raise ValueError('다른 요청이 진행 중입니다. 중단 요청 또는 아이디어 기록만 가능합니다.')
            job = {'id': secrets.token_hex(8), 'project': key, 'action': action, 'status': 'running', 'started_at': c.now()}
            self.jobs.append(job)
            self.jobs = self.jobs[-100:]
            threading.Thread(target=self.execute, args=(job, args), daemon=True).start()
            return dict(job)

    def execute(self, job, args):
        # shell=False: user text is one argument, never a PowerShell command.
        directory = ROOT/'.runtime-dashboard'
        try:
            directory.mkdir(exist_ok=True)
            path = directory/(job['id']+'.log')
            with path.open('wb') as log:
                result = subprocess.run(args, cwd=ROOT.parent, stdout=log, stderr=subprocess.STDOUT,
                                        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            with self.mutex:
                job.update(status='completed' if result.returncode == 0 else 'failed', exit_code=result.returncode,
                           log_file=str(path), finished_at=c.now())
        except Exception:
            with self.mutex: job.update(status='failed', finished_at=c.now())


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, port, backend):
        super().__init__(('127.0.0.1', port), Handler)
        self.backend = backend
        self.token = secrets.token_urlsafe(32)
        self.origin = 'http://127.0.0.1:' + str(self.server_port)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def reply(self, status, value, content_type='application/json; charset=utf-8'):
        body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.send_header('Referrer-Policy', 'no-referrer')
        self.end_headers()
        self.wfile.write(body)

    def trusted(self):
        return (self.headers.get('Host') == urlsplit(self.server.origin).netloc
                and self.headers.get('Origin', self.server.origin) == self.server.origin
                and self.headers.get('Sec-Fetch-Site', 'same-origin') in ('same-origin', 'none'))

    def do_GET(self):
        if not self.trusted(): return self.reply(403, {'error': '로컬 화면에서 접속하세요.'})
        url = urlsplit(self.path)
        files = {'/': ('index.html', 'text/html; charset=utf-8'), '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                 '/style.css': ('style.css', 'text/css; charset=utf-8')}
        try:
            if url.path in files:
                name, kind = files[url.path]
                return self.reply(200, (ROOT/'dashboard_assets'/name).read_bytes(), kind)
            if url.path == '/api/session':
                return self.reply(200, {'token': self.server.token, 'projects': self.server.backend.projects()})
            if url.path == '/api/state':
                key = parse_qs(url.query).get('project', ['hanjari'])[0]
                return self.reply(200, self.server.backend.snapshot(key))
            return self.reply(404, {'error': '찾을 수 없는 경로입니다.'})
        except Exception:
            return self.reply(500, {'error': '상태를 읽지 못했습니다. config.json과 프로젝트 상태 파일을 확인하세요.'})

    def do_POST(self):
        if not self.trusted() or not secrets.compare_digest(self.headers.get('X-Dashboard-Token', ''), self.server.token):
            return self.reply(403, {'error': '화면을 새로고침한 뒤 다시 시도하세요.'})
        if self.path != '/api/action': return self.reply(404, {'error': '잘못된 경로입니다.'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 64000 or self.headers.get('Content-Type') != 'application/json':
                return self.reply(400, {'error': '잘못된 요청 형식입니다.'})
            payload = json.loads(self.rfile.read(size))
            return self.reply(202, self.server.backend.submit(payload))
        except ValueError as exc: return self.reply(409, {'error': str(exc)})
        except Exception: return self.reply(500, {'error': '요청을 처리하지 못했습니다. 설정 및 실행 상태를 확인하세요.'})


def main():
    parser = argparse.ArgumentParser(description='HanJari local automation dashboard')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--config', type=Path, default=ROOT/'config.json')
    parser.add_argument('--mode', choices=['live', 'mock'], default='live')
    args = parser.parse_args()
    server = Server(args.port, Backend(args.config, args.mode))
    print('Dashboard: ' + server.origin, flush=True)
    print('Opening the page does not start agents. Keep this terminal open; Ctrl+C stops the dashboard.', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == '__main__': main()
