import copy
import threading
import tkinter as tk
import unittest
from unittest.mock import Mock, patch
import desktop as d


def state():
    return {'project':{'id':'hanjari','title':'한자리'},'mode':'live','stage':'awaiting_plan_approval',
            'parent_stage':'awaiting_plan_approval','parent_revision':2,'candidate_hash':'a'*64,'approved':False,
            'active':False,'running':None,'busy':False,'document':'# 실제 기획안\n본문',
            'features':[],'results':[],'events':[],'jobs':[],'usage':None}


class DesktopTests(unittest.TestCase):
    def test_no_execution_before_approval(self):
        self.assertNotIn('run',d.allowed_actions(state()))
        self.assertIn('approve',d.allowed_actions(state()))
        s=state();s['approved']=True
        self.assertIn('run',d.allowed_actions(s));self.assertNotIn('approve',d.allowed_actions(s))

    def test_busy_only_allows_pause_and_note(self):
        for key in ('busy','active','running'):
            s=state();s[key]=True
            self.assertEqual(d.allowed_actions(s),{'pause','note'})

    def test_dialog_keeps_exact_reviewed_hash(self):
        s=state();payload=d.payload_for(s,'approve');s['candidate_hash']='b'*64
        self.assertEqual(payload['hash'],'a'*64)

    def test_accept_requires_current_completed_feature(self):
        s=state();s['stage']='pr_ready'
        self.assertNotIn('accept-feature',d.allowed_actions(s))
        s['current_feature']='HJ-002'
        self.assertIn('accept-feature',d.allowed_actions(s))

    def test_native_render_does_not_start_server_or_agents(self):
        root=tk.Tk();root.withdraw()
        self.addCleanup(root.destroy)
        backend=Mock();backend.mode='live';backend.jobs=[];backend.mutex=threading.Lock()
        with patch('dashboard.Server') as server:
            app=d.Desktop(root,backend,poll=False)
            s=state();app.render(s);root.update_idletasks()
            self.assertIn('승인 대기',app.status.cget('text'))
            self.assertIn('실제 기획안',app.plan_text.get('1.0','end'))
            self.assertEqual(app.buttons['run'][0].cget('state'),'disabled')
            self.assertEqual(app.buttons['approve'][0].cget('state'),'normal')
            server.assert_not_called();backend.submit.assert_not_called()

    def test_close_keeps_window_while_job_running(self):
        root=tk.Tk();root.withdraw()
        self.addCleanup(root.destroy)
        backend=Mock();backend.mode='live';backend.jobs=[{'status':'running'}];backend.mutex=threading.Lock()
        app=d.Desktop(root,backend,poll=False)
        with patch.object(d.messagebox,'showinfo') as alert:app.close()
        self.assertFalse(app.closed);self.assertTrue(root.winfo_exists());alert.assert_called_once()

    def test_polling_does_not_destroy_text_selection(self):
        root=tk.Tk();root.withdraw();self.addCleanup(root.destroy)
        text=tk.Text(root)
        d.replace_text(text,'hello world');text.tag_add('sel','1.0','1.5')
        d.replace_text(text,'hello world')
        self.assertEqual(text.get('sel.first','sel.last'),'hello')


if __name__=='__main__':unittest.main()
