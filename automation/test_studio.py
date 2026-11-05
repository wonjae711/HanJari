import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import dashboard as d
import studio


class StudioServerTests(unittest.TestCase):
    def setUp(self):
        self.backend = Mock()
        self.backend.projects.return_value = [{'id': 'hanjari', 'title': '한자리'}]
        self.backend.snapshot.return_value = {'stage': 'awaiting_plan_approval'}
        self.route = Mock(return_value={'ok': True})
        self.server = d.Server(0, self.backend, files=studio.FILES, routes={'/api/pick': self.route})
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()

    def request(self, path, method='GET', body=None, headers=None):
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port)
        conn.request(method, path, body=body, headers=headers or {})
        response = conn.getresponse()
        result = (response.status, response.read(), dict(response.getheaders()))
        conn.close()
        return result

    def test_serves_only_listed_studio_assets(self):
        for path in studio.FILES:
            self.assertEqual(self.request(path)[0], 200, path)
        self.assertEqual(self.request('/fonts/../config.json')[0], 404)
        self.assertEqual(self.request('/dashboard_assets/app.js')[0], 404)

    def test_extra_routes_need_token_and_never_start_agents(self):
        body = json.dumps({'kind': 'folder'})
        self.assertEqual(self.request('/api/pick', 'POST', body, {'Content-Type': 'application/json'})[0], 403)
        headers = {'Content-Type': 'application/json', 'X-Dashboard-Token': self.server.token, 'Origin': self.server.origin}
        self.assertEqual(self.request('/api/pick', 'POST', body, headers)[0], 200)
        self.route.assert_called_once_with({'kind': 'folder'})
        self.backend.submit.assert_not_called()
        self.assertEqual(self.request('/api/unknown', 'POST', body, headers)[0], 404)

    def test_route_errors_are_reported_as_conflict(self):
        self.route.side_effect = ValueError('이미 선택 창이 열려 있습니다.')
        headers = {'Content-Type': 'application/json', 'X-Dashboard-Token': self.server.token, 'Origin': self.server.origin}
        status, body, _ = self.request('/api/pick', 'POST', json.dumps({'kind': 'json'}), headers)
        self.assertEqual(status, 409)
        self.assertIn('선택 창', json.loads(body)['error'])


class StudioActionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name) / 'repo'; self.repo.mkdir()
        (self.repo / 'Main.java').write_text('class Main {}')
        (Path(self.tmp.name) / 'outside.txt').write_text('secret')
        self.backend = Mock(); self.backend.mutex = threading.Lock(); self.backend.jobs = []
        self.backend.profile.return_value = ({'project_dir': str(self.repo)}, None, None)
        self.studio = studio.Studio(self.backend)

    def test_open_is_limited_to_project_files_and_never_executes(self):
        with patch.object(studio.subprocess, 'Popen') as popen:
            self.studio.open({'project': 'hanjari', 'path': 'Main.java'})
            command = popen.call_args[0][0]
            self.assertIn(str((self.repo / 'Main.java').resolve()), command)
            self.assertTrue(command[0].endswith(('Code.exe', 'notepad.exe')))
            for path in ['../outside.txt', str(Path(self.tmp.name) / 'outside.txt'), 'missing.java']:
                with self.assertRaises(ValueError):
                    self.studio.open({'project': 'hanjari', 'path': path})
            with self.assertRaises(ValueError):
                self.studio.open({'kind': 'log', 'path': str(self.repo / 'Main.java')})
            self.assertEqual(popen.call_count, 1)

    def test_pick_rejects_unknown_kind(self):
        with self.assertRaises(ValueError):
            self.studio.pick({'kind': 'exe'})

    def test_register_blocked_while_a_request_runs(self):
        self.backend.jobs = [{'status': 'running'}]
        with patch('project_registration.register_project') as register:
            with self.assertRaises(ValueError):
                self.studio.register({'title': 'x'})
            register.assert_not_called()


if __name__ == '__main__':
    unittest.main()
