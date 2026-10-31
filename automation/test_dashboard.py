import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
import dashboard as d
import controller as c


class BackendTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = c.Store(self.tmp.name)
        self.backend = d.Backend(Path(self.tmp.name)/'config.json')
        self.mock = patch.object(self.backend,'profile',return_value=({'id':'hanjari','title':'한자리'},self.store,{}))
        self.mock.start(); self.addCleanup(self.mock.stop)

    def test_text_is_single_argument_not_shell_program(self):
        attack = 'hello; $(Remove-Item files) "quoted"'
        action,key,args=self.backend.command({'action':'note','project':'hanjari','text':attack})
        self.assertEqual(args[-2:],['--text',attack])
        self.assertEqual(action,'note')
        self.assertIn('--mode',args)

    def test_unknown_action_and_empty_revision_rejected(self):
        for payload in [{'action':'delete','project':'hanjari'},{'action':'revise','project':'hanjari','text':' '}]:
            with self.assertRaises(ValueError): self.backend.command(payload)

    def test_stale_plan_hash_never_approves(self):
        c.save(self.store.path,{'candidate_hash':'a'*64})
        with self.assertRaises(ValueError):self.backend.command({'action':'approve','project':'hanjari','hash':'b'*64})
        self.assertEqual(self.backend.command({'action':'approve','project':'hanjari','hash':'a'*64})[-1][-1],'a'*64)

    def test_page_load_does_not_initialize_state_or_start_process(self):
        with patch.object(d.subprocess,'run') as run:
            result=self.backend.snapshot('hanjari')
        self.assertEqual(result['stage'],'not_started')
        self.assertFalse(self.store.path.exists());run.assert_not_called()

    def test_duplicate_actions_blocked_pause_allowed(self):
        self.backend.jobs=[{'project':'hanjari','action':'run','status':'running'}]
        with self.assertRaises(ValueError):self.backend.submit({'action':'run','project':'hanjari'})
        with patch.object(d.threading,'Thread') as thread:
            self.backend.submit({'action':'pause','project':'hanjari'})
            thread.return_value.start.assert_called_once()
        with self.assertRaises(ValueError):self.backend.submit({'action':'pause','project':'hanjari'})

    def test_invalid_and_stale_usage_not_reported_as_fresh(self):
        path=Path(self.tmp.name)/'usage.json'
        c.save(path,{'five_hour':{'used_percentage':True},'seven_day':{'used_percentage':10}})
        self.assertIsNone(self.backend.usage({'usage_file':str(path)}))
        c.save(path,{'five_hour':{'used_percentage':25},'seven_day':{'used_percentage':40}})
        with patch.object(d.time,'time',return_value=path.stat().st_mtime+301):
            self.assertFalse(self.backend.usage({'usage_file':str(path)})['fresh'])

    def test_project_identifier_cannot_be_path(self):
        backend=d.Backend('not-read.json')
        with self.assertRaises(ValueError):backend.profile('../../elsewhere')


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.backend=Mock()
        self.backend.projects.return_value=[{'id':'hanjari','title':'한자리'}]
        self.backend.snapshot.return_value={'stage':'awaiting_plan_approval'}
        self.backend.submit.return_value={'status':'running'}
        self.server=d.Server(0,self.backend)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown();self.server.server_close();self.thread.join()

    def request(self,path,method='GET',body=None,headers=None):
        conn=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        conn.request(method,path,body=body,headers=headers or {})
        response=conn.getresponse();result=(response.status,response.read(),dict(response.getheaders()));conn.close();return result

    def test_readonly_routes_and_no_directory_serving(self):
        for path in ['/','/app.js','/style.css','/api/session','/api/state?project=hanjari']:
            status,body,headers=self.request(path);self.assertEqual(status,200)
            self.assertEqual(headers['Cache-Control'],'no-store')
        self.backend.submit.assert_not_called()
        self.assertEqual(self.request('/../config.json')[0],404)
        self.assertEqual(self.request('/api/config')[0],404)

    def test_cross_site_and_rebinding_requests_rejected(self):
        for headers in [{'Host':'evil.test'},{'Origin':'https://evil.test'},{'Sec-Fetch-Site':'cross-site'}]:
            self.assertEqual(self.request('/api/session',headers=headers)[0],403)

    def test_action_requires_session_token_and_json(self):
        payload=json.dumps({'action':'run','project':'hanjari'})
        self.assertEqual(self.request('/api/action','POST',payload,{'Content-Type':'application/json'})[0],403)
        headers={'Content-Type':'application/json','X-Dashboard-Token':self.server.token,'Origin':self.server.origin}
        self.assertEqual(self.request('/api/action','POST',payload,headers)[0],202)
        self.backend.submit.assert_called_once_with({'action':'run','project':'hanjari'})
        headers['Content-Type']='text/plain'
        self.assertEqual(self.request('/api/action','POST',payload,headers)[0],400)


if __name__=='__main__':unittest.main()
