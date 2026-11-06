import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock,patch
import prompt_library as p


class PromptTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        switch=patch.object(p,'ROOT',self.root);switch.start();self.addCleanup(switch.stop)
        self.backend=Mock();self.backend.mode='mock';self.backend.jobs=[];self.backend.mutex=threading.Lock()
        self.profile={'id':'alpha','title':'책장','idea':'독서 기록'}
        self.backend.profile.return_value=(self.profile,None,{})
        self.state={'stage':'not_started','document':''}
        self.backend.snapshot.return_value=self.state
        self.library=p.Library(self.backend)

    def test_list_is_read_only_and_empty(self):
        with patch.object(p,'generate') as generate:
            self.assertEqual(self.library.handle({'project':'alpha'})['records'],[])
            generate.assert_not_called();self.backend.submit.assert_not_called()

    def test_explicit_generation_is_project_scoped_and_preserves_state(self):
        with patch.object(p.threading,'Thread') as thread:
            job=self.library.handle({'project':'alpha','operation':'generate','kind':'design','notes':'모바일 우선'})
            args=thread.call_args.kwargs['args']
            thread.return_value.start.assert_called_once()
        self.library.work(*args)
        result=p.records('alpha')
        self.assertEqual(len(result),1);self.assertEqual(result[0]['mode'],'mock')
        self.assertEqual(p.records('bravo'),[])
        self.assertEqual(self.state,{'stage':'not_started','document':''})
        self.assertEqual(self.backend.jobs[0]['status'],'completed')

    def test_busy_invalid_and_timeout_do_not_retry(self):
        self.backend.jobs=[{'status':'running'}]
        with self.assertRaises(ValueError):self.library.handle({'project':'alpha','operation':'generate','kind':'design'})
        with self.assertRaises(ValueError):p.directory('../alpha')
        self.backend.mode='live';job={'id':'1','project':'alpha','status':'running','started_at':'now'}
        data=p.context(self.profile,self.state,'design','')
        with patch.object(p,'generate',side_effect=TimeoutError) as generate:
            self.library.work(job,{},data,'design');generate.assert_called_once()
        self.assertEqual(job['status'],'failed');self.assertEqual(p.records('alpha'),[])

    def test_codex_command_and_environment(self):
        folder=self.root/'job';folder.mkdir()
        data=p.context(self.profile,self.state,'test','')
        def run(args,**kwargs):
            self.assertEqual(kwargs['cwd'],folder)
            self.assertNotIn('HANJARI_TEST_DB_PASSWORD',kwargs['env'])
            self.assertIn('read-only',args);self.assertIn('--ephemeral',args)
            self.assertIn('독서 기록',kwargs['input'])
            p.c.save(folder/'result.json',{'title':'테스트 계획','prompt':'정상·예외 테스트를 설계하세요.'})
            return Mock(returncode=0)
        with patch.object(p.c,'child_environment',return_value={'HANJARI_TEST_DB_PASSWORD':'secret'}),patch.object(p.c,'executable',return_value='codex.exe'),patch.object(p.subprocess,'run',side_effect=run):
            self.assertIn('예외',p.generate({},data,'test',folder)['prompt'])

    def test_api_key_blocks_before_process(self):
        with patch.object(p.c,'child_environment',return_value={'OPENAI_API_KEY':'secret'}),patch.object(p.subprocess,'run') as run:
            with self.assertRaises(ValueError):p.generate({}, {},'design',self.root)
            run.assert_not_called()
