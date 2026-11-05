"""HanJari Studio: the Claude Design mockup UI in an Edge app window.

Starts the loopback-only dashboard server on a random port and opens
studio_assets/ with `msedge --app` (no address bar, separate profile).
Opening the window never starts an agent; all actions reuse Backend.submit.
"""
import argparse
import db_settings
import ctypes
import hashlib
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

from dashboard import Backend, ROOT, Server

ASSETS = 'studio_assets'
FILES = {
    '/': (ASSETS + '/index.html', 'text/html; charset=utf-8'),
    '/app.js': (ASSETS + '/app.js', 'text/javascript; charset=utf-8'),
    '/style.css': (ASSETS + '/style.css', 'text/css; charset=utf-8'),
    '/favicon.ico': ('desktop_assets/hanjari.ico', 'image/x-icon'),
    '/fonts/PretendardVariable.woff2': (ASSETS + '/fonts/PretendardVariable.woff2', 'font/woff2'),
    '/fonts/JetBrainsMono.woff2': (ASSETS + '/fonts/JetBrainsMono.woff2', 'font/woff2'),
}
LOG_DIR = ROOT / '.runtime-dashboard'
PROFILE_DIR = ROOT / '.runtime-studio' / 'edge-profile'
EDGE_CANDIDATES = [
    Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Microsoft/Edge/Application/msedge.exe',
    Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Microsoft/Edge/Application/msedge.exe',
    Path(os.environ.get('LOCALAPPDATA', '')) / 'Microsoft/Edge/Application/msedge.exe',
]
PICK_SCRIPT = r'''
Add-Type -AssemblyName System.Windows.Forms
$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true; ShowInTaskbar = $false}
if ($env:HJ_PICK -eq 'folder') {
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = 'Git 저장소 폴더를 선택하세요'
    if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.SelectedPath) }
} else {
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Filter = 'JSON (*.json)|*.json'
    if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.FileName) }
}
'''


def message(text, title='한자리 스튜디오'):
    if os.name == 'nt':
        ctypes.windll.user32.MessageBoxW(None, text, title, 0x40)
    else:
        print(text, file=sys.stderr)


def instance_handle(identity):
    """Same mutex name as desktop.py, so the Tk and Edge apps never run together."""
    if os.name != 'nt':
        return None
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_bool, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    name = 'Local\\HanJariDesktop-' + hashlib.sha256(identity.encode()).hexdigest()[:24]
    handle = kernel.CreateMutexW(None, False, name)
    error = ctypes.get_last_error()
    if not handle:
        raise OSError('Desktop instance lock failed')
    if error == 183:
        kernel.CloseHandle(handle)
        raise ValueError('한자리가 이미 실행 중입니다. 작업 표시줄에서 기존 창을 열어 주세요.')
    return kernel, handle


def find_edge():
    for path in EDGE_CANDIDATES:
        if path.is_file():
            return path
    found = shutil.which('msedge')
    if found:
        return Path(found)
    raise FileNotFoundError('Microsoft Edge를 찾지 못했습니다. Edge 설치 후 다시 실행하세요.')


def editor_command(path):
    """Open files for reading only: VS Code if installed, otherwise Notepad. Never 'run' a file."""
    code = shutil.which('code')
    if code:
        exe = Path(code).resolve().parent.parent / 'Code.exe'
        if exe.is_file():
            return [str(exe), '--goto', str(path)]
    return ['notepad.exe', str(path)]


def inside(child, parent):
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


class Studio:
    def __init__(self, backend):
        self.backend = backend
        self.picking = threading.Lock()

    def routes(self):
        return {'/api/register': self.register, '/api/pick': self.pick, '/api/open': self.open, '/api/db-settings': self.database, '/api/settings': self.settings}

    def settings(self, payload):
        if not isinstance(payload, dict):
            raise ValueError('잘못된 요청입니다.')
        profile, _, _ = self.backend.profile(payload.get('project'))
        base = Path(profile['project_dir']).resolve()
        candidates = {'.env', '.env.example', 'README.md', 'AGENTS.md', 'CLAUDE.md'}
        for pattern in ('.env.*', 'src/main/resources/application*.yml',
                        'src/main/resources/application*.yaml', 'src/main/resources/application*.properties',
                        'src/test/resources/application*.yml', 'src/test/resources/application*.yaml',
                        'src/test/resources/application*.properties'):
            for path in base.glob(pattern):
                candidates.add(path.relative_to(base).as_posix())
        files = []
        for name in sorted(candidates):
            target = (base / name).resolve()
            if not inside(target, base):
                continue
            files.append({'path': name, 'exists': target.is_file(),
                          'kind': '환경변수' if name.startswith('.env') else '애플리케이션' if 'application' in name else '프로젝트 안내'})
        try:
            saved = db_settings.public(profile['id'])['saved']
            error = None
        except ValueError:
            saved = False
            error = '저장된 DB 설정을 읽지 못했습니다.'
        return {'project': profile['id'], 'folder': str(base), 'files': files,
                'database_saved': saved, 'database_error': error}

    def database(self, payload):
        if not isinstance(payload, dict):
            raise ValueError('잘못된 요청입니다.')
        key = payload.get('project')
        self.backend.profile(key)  # registered project only
        operation = payload.get('operation', 'read')
        with self.backend.mutex:
            if operation == 'read':
                return db_settings.public(key)
            if any(j['status'] == 'running' for j in self.backend.jobs):
                raise ValueError('현재 요청이 끝난 후 DB 설정을 변경하세요.')
            if operation == 'save':
                return db_settings.save(key, payload)
            if operation == 'delete':
                db_settings.path_for(key).unlink(missing_ok=True)
                return {'saved': False, 'url': '', 'username': ''}
            raise ValueError('지원하지 않는 DB 설정 요청입니다.')

    def register(self, payload):
        from project_registration import register_project
        if not isinstance(payload, dict):
            raise ValueError('잘못된 요청입니다.')
        fields = ('title', 'project', 'repo', 'record_page', 'source_page', 'prefix', 'baseline', 'idea')
        values = {k: str(payload.get(k) or '') for k in fields}
        if any(len(v) > 12000 for v in values.values()):
            raise ValueError('입력값이 너무 깁니다.')
        with self.backend.mutex:
            if any(j['status'] == 'running' for j in self.backend.jobs):
                raise ValueError('진행 중인 요청이 끝난 후 등록하세요.')
            result = register_project(values)
        return {'id': result['id'], 'title': result['title']}

    def pick(self, payload):
        kind = payload.get('kind') if isinstance(payload, dict) else None
        if kind not in ('folder', 'json'):
            raise ValueError('잘못된 요청입니다.')
        if not self.picking.acquire(blocking=False):
            raise ValueError('이미 선택 창이 열려 있습니다.')
        try:
            result = subprocess.run(
                ['powershell.exe', '-NoProfile', '-STA', '-ExecutionPolicy', 'Bypass', '-Command', PICK_SCRIPT],
                capture_output=True, env=dict(os.environ, HJ_PICK=kind), timeout=600,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            return {'path': result.stdout.decode('utf-8', 'replace').strip()}
        finally:
            self.picking.release()

    def open(self, payload):
        if not isinstance(payload, dict) or not isinstance(payload.get('path'), str):
            raise ValueError('잘못된 요청입니다.')
        if payload.get('kind') == 'log':
            target = Path(payload['path']).resolve()
            if not (inside(target, LOG_DIR.resolve()) and target.suffix == '.log' and target.is_file()):
                raise ValueError('이 앱이 만든 로그 파일만 열 수 있습니다.')
        else:
            profile, _, _ = self.backend.profile(payload.get('project'))
            base = Path(profile['project_dir']).resolve()
            target = (base / payload['path']).resolve()
            if not (inside(target, base) and target.is_file()):
                raise ValueError('프로젝트 폴더 안의 파일만 열 수 있습니다.')
        subprocess.Popen(editor_command(target), creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        return {'opened': True}


def running_jobs(backend):
    with backend.mutex:
        return any(j['status'] == 'running' for j in backend.jobs)


def main():
    parser = argparse.ArgumentParser(description='HanJari Studio (Edge app window)')
    parser.add_argument('--config', type=Path, default=ROOT / 'config.json')
    parser.add_argument('--mode', choices=['live', 'mock'], default='live')
    parser.add_argument('--port', type=int, default=0, help='0 = random free loopback port')
    parser.add_argument('--no-window', action='store_true', help='serve only; open the printed URL yourself')
    args = parser.parse_args()
    handle = None
    server = None
    try:
        handle = instance_handle(str(args.config.resolve()).casefold() + ':' + args.mode)
        backend = Backend(args.config, args.mode)
        backend.projects()  # fail early on a broken config, before opening a window
        studio = Studio(backend)
        server = Server(args.port, backend, files=FILES, routes=studio.routes())
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = server.origin + '/'
        if args.no_window:
            print('Studio: ' + url, flush=True)
            while True:
                time.sleep(3600)
        PROFILE_DIR.mkdir(parents=True, exist_ok=True)
        window = subprocess.Popen([
            str(find_edge()), '--app=' + url, '--user-data-dir=' + str(PROFILE_DIR),
            '--no-first-run', '--no-default-browser-check', '--disable-sync',
            '--window-size=1440,900', '--disable-features=Translate,msEdgeSidebarV2,msUndersideButton'])
        window.wait()
        # Requests already started keep running; stay alive until they report back.
        while running_jobs(backend):
            time.sleep(2)
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        message(str(exc))
    finally:
        if server:
            server.shutdown()
            server.server_close()
        if handle:
            handle[0].CloseHandle(handle[1])


if __name__ == '__main__':
    main()
