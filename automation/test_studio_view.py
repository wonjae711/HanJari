import copy
import threading
import tkinter as tk
import unittest
from unittest.mock import Mock,patch
import studio_view as v
from test_desktop import state


class PolicyTests(unittest.TestCase):
    def test_claude_gate_not_applied_to_codex_planning(self):
        s=state();s['stage']='plan';s['parent_stage']='plan';s['usage']=None
        self.assertIn('run',v.control_actions(s))
        s['approved']=True
        self.assertNotIn('run',v.control_actions(s))
        s['usage']={'five_hour':79,'seven_day':69,'fresh':True,'age_seconds':2}
        self.assertIn('run',v.control_actions(s))
        s['usage']['seven_day']=70
        self.assertNotIn('run',v.control_actions(s))

    def test_unanswered_questions_and_latched_breaker_block_actions(self):
        s=state();s['questions']=['정책 결정 필요']
        self.assertNotIn('approve',v.control_actions(s))
        s['stage']='implement';s['run_guard']={'latched':True}
        self.assertNotIn('run',v.control_actions(s))

    def test_pause_status_does_not_claim_immediate_stop(self):
        s=state();s.update(pause_requested=True,active=True)
        self.assertIn('중단 요청',v.status_chip(s)[0])
        s['active']=False
        self.assertIn('중단됨',v.status_chip(s)[0])

    def test_owner_only_names_active_agent(self):
        s=state();self.assertEqual(v.owner(s)[0],'나')
        s.update(stage='implement',active=True)
        self.assertEqual(v.owner(s)[0],'Claude Code')
        s['stage']='review';self.assertEqual(v.owner(s)[0],'Codex')


class StudioTests(unittest.TestCase):
    def setUp(self):
        self.root=tk.Tk();self.root.withdraw();self.addCleanup(self.root.destroy)
        self.backend=Mock();self.backend.mode='live';self.backend.jobs=[];self.backend.mutex=threading.Lock()
        self.app=v.Studio(self.root,self.backend,poll=False)
        self.s=state();self.s.update(questions=[],confirmed_constraints=['실제 결제 없음'],proposals=['모의 결제 제안'])
        self.app.render(self.s);self.app.stale=False

    def test_render_and_navigation_are_read_only(self):
        for key,_ in v.NAV:self.app.show(key)
        self.root.update_idletasks()
        self.backend.submit.assert_not_called()
        self.assertEqual(len(self.app.nav),5)
        self.assertIn('실제 결제 없음',self.app.decision_text.cget('text'))
        self.assertEqual(self.app.buttons['approve'][0].cget('state'),'disabled')

    def test_plan_stays_pinned_until_user_adopts_new_version(self):
        self.app.read_check.set(True);self.app.refresh_controls()
        self.assertEqual(self.app.buttons['approve'][0].cget('state'),'normal')
        updated=copy.deepcopy(self.s);updated.update(candidate_hash='b'*64,document='# 새로운 기획안\n새 본문')
        self.app.render(updated)
        self.assertTrue(self.app.plan_stale)
        self.assertIn('실제 기획안',self.app.plan_text.get('1.0','end'))
        self.assertEqual(self.app.buttons['approve'][0].cget('state'),'disabled')
        self.app.adopt_plan()
        self.assertFalse(self.app.plan_stale);self.assertFalse(self.app.read_check.get())
        self.assertIn('새로운 기획안',self.app.plan_text.get('1.0','end'))

    def test_recovery_requires_resolution_acknowledgement(self):
        self.s['receipts']=[{'status':'needs_user','kind':'document_receipts'}]
        self.app.render(self.s)
        self.assertEqual(self.app.buttons['retry-sync'][0].cget('state'),'disabled')
        self.app.recovery_check.set(True);self.app.refresh_controls()
        self.assertEqual(self.app.buttons['retry-sync'][0].cget('state'),'normal')

    def test_search_counts_matches_without_changing_plan(self):
        self.app.search_var.set('기획');self.app.search_plan()
        self.assertIn('1 / 1',self.app.search_status.cget('text'))
        self.app.search_var.set('없는 단어');self.app.search_plan()
        self.assertEqual(self.app.search_status.cget('text'),'결과 없음')
        self.assertEqual(self.app.state['candidate_hash'],'a'*64)

    def test_feature_filter_and_selection(self):
        s=copy.deepcopy(self.s)
        s['features']=[{'task_id':'HJ-002','title':'가입','requirements':'가입 개발','acceptance_criteria':['중복 거절'],'excluded':[],'depends_on':[]}]
        self.app.render(s)
        self.assertEqual(self.app.tree.get_children(),('HJ-002',))
        self.app.feature_filter.set('없는 기능')
        self.assertFalse(self.app.tree.get_children())
        self.assertIn('검색 결과',self.app.feature_text.get('1.0','end'))
        self.app.feature_filter.set('가입')
        self.assertEqual(self.app.tree.get_children(),('HJ-002',))


if __name__=='__main__':unittest.main()
