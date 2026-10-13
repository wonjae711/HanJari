import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, Mock
import controller as c
import project_planning as p
import project
import knowledge as k
import delivery as d
from verification_evidence import task_policy, check_reports

class PlanningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.store = c.Store(self.root / 'project')
        project.initialize(self.store, 'mock', {'text': 'source', 'sha256': 'source', 'page_id': project.SOURCE_PAGE})

    def run_plan(self, runner=None):
        self.store.update(lambda s: s.update(active=True))
        c.run(self.store, {}, runner=runner)
        return c.read(self.store.path)

    def approved(self):
        state = self.run_plan()
        project.approve(self.store, state['candidate_hash'], {})
        return c.read(self.store.path)

    def test_independent_review_receives_draft_and_returns_to_author(self):
        calls = []
        def run(cfg, state, directory):
            calls.append(state['stage'])
            result = p.mock_result(state)
            if state['stage'] == 'spec_review' and calls.count('spec_review') == 1:
                self.assertIn('MOCK plan', p.prompt(state))
                result['outcome'] = 'changes'
                result['artifact']['findings'] = ['Missing expiry boundary']
            if state['stage'] == 'plan' and calls.count('plan') == 2:
                self.assertIn('Missing expiry boundary', p.prompt(state))
            return result
        state = self.run_plan(run)
        self.assertEqual(calls, ['plan', 'spec_review', 'plan', 'spec_review'])
        self.assertEqual(state['stage'], 'awaiting_plan_approval')
        self.assertEqual(state['planning_repairs'], 1)
        self.assertFalse(state['active'])
        self.assertTrue((self.store.directory/'artifacts/approval.txt').exists())

    def test_three_repairs_manager_once_then_human(self):
        calls = []
        def run(cfg, state, directory):
            calls.append(state['stage'])
            result = p.mock_result(state)
            if state['stage'] == 'spec_review':
                result['outcome'] = 'changes'
                result['artifact']['findings'] = ['Not resolved']
            return result
        state = self.run_plan(run)
        self.assertEqual(calls.count('plan'), 5)
        self.assertEqual(calls.count('spec_review'), 5)
        self.assertEqual(calls.count('manager'), 1)
        self.assertEqual(state['planning_repairs'], 3)
        self.assertEqual(state['stage'], 'needs_user')

    def test_questions_prevent_approval(self):
        def run(cfg, state, directory):
            result = p.mock_result(state)
            if state['stage'] == 'plan': result['artifact']['questions'] = ['Business decision?']
            return result
        state = self.run_plan(run)
        self.assertEqual(state['stage'], 'needs_user')
        with self.assertRaises(ValueError): project.approve(self.store, p.plan_hash(state), {})

    def test_reviewer_cannot_pass_findings_or_replace_plan(self):
        state = self.run_plan()
        state['stage'] = 'spec_review'
        for field, value in [('findings', ['Missing expiry']), ('document', 'Silent rewrite')]:
            result = p.mock_result(state)
            result['artifact'][field] = value
            with self.assertRaises(ValueError): c.validate(result, state)

    def test_invalid_features_cannot_pass(self):
        state = c.read(self.store.path)
        for change in [lambda f: f.update(task_id='../escape'),
                       lambda f: f.update(depends_on=['HJ-099']),
                       lambda f: f.update(unit_suites=[]),
                       lambda f: f['unit_suites'][0].update(minimum_tests=0)]:
            result = p.mock_result(state)
            change(result['artifact']['features'][0])
            with self.assertRaises(ValueError): c.validate(result, state)

    def test_no_handoff_before_exact_approval(self):
        state = self.run_plan()
        with self.assertRaises(ValueError): project.handoff(self.store, 'HJ-002')
        with self.assertRaises(ValueError): project.approve(self.store, 'wrong', {})
        project.approve(self.store, state['candidate_hash'], {})
        child = project.handoff(self.store, 'HJ-002')
        saved = c.read(child.path)
        self.assertEqual(saved['stage'], 'implement')
        self.assertFalse(saved['active'])
        self.assertEqual(saved['mode'], 'mock')
        project.verify_handoff(saved)
        saved['mode'] = 'live'
        with self.assertRaises(ValueError): project.verify_handoff(saved)

    def test_changed_source_prevents_live_approval(self):
        state = self.run_plan()
        self.store.update(lambda s: s.update(mode='live'))
        with patch.object(k, 'snapshot', return_value={'sha256': 'changed'}):
            with self.assertRaises(ValueError): project.approve(self.store, state['candidate_hash'], {})

    def test_changed_feature_or_plan_invalidates_handoff(self):
        self.approved()
        child = project.handoff(self.store, 'HJ-002')
        state = c.read(child.path)
        state['task']['requirements'] = 'Unapproved scope'
        with self.assertRaises(ValueError): project.verify_handoff(state)
        project.revise(self.store, 'New requirement')
        with self.assertRaises(ValueError): project.verify_handoff(c.read(child.path))
        parent = c.read(self.store.path)
        self.assertEqual(parent['revision'], 2)
        self.assertNotIn('approved_plan', parent)
        self.assertEqual(len(parent['results']), 2)

    def test_second_feature_waits_for_human_review(self):
        def run(cfg, state, directory):
            result = p.mock_result(state)
            if state['stage'] == 'plan':
                feature = copy.deepcopy(result['artifact']['features'][0])
                feature.update(task_id='HJ-003', depends_on=['HJ-002'])
                feature['unit_suites'][0]['name'] = 'BookingTest'
                feature['integration_suites'][0]['name'] = 'BookingIT'
                result['artifact']['features'].append(feature)
            return result
        state = self.run_plan(run)
        project.approve(self.store, state['candidate_hash'], {})
        with self.assertRaises(ValueError): project.handoff(self.store, 'HJ-003')
        child = project.handoff(self.store, 'HJ-002')
        with self.assertRaises(ValueError): project.accept_feature(self.store, 'HJ-002', 'reviewed')
        child.update(lambda s: s.update(stage='pr_ready'))
        project.accept_feature(self.store, 'HJ-002', 'MOCK reviewed')
        second = project.handoff(self.store, 'HJ-003')
        policy = task_policy(c.read(second.path)['task'])
        self.assertIn('MemberTest', policy['surefire-reports'])
        self.assertIn('BookingTest', policy['surefire-reports'])
        self.assertIn('MovieControllerTest', policy['surefire-reports'])

    def test_handoff_repeated_does_not_reset_existing_state(self):
        self.approved()
        child = project.handoff(self.store, 'HJ-002')
        child.update(lambda s: s.update(stage='pr_ready'))
        project.handoff(self.store, 'HJ-002')
        self.assertEqual(c.read(child.path)['stage'], 'pr_ready')

    def test_project_lock_blocks_revision_during_child_run(self):
        self.approved()
        child = project.handoff(self.store, 'HJ-002')
        child.update(lambda s: s.update(active=True))
        def run(cfg, state, directory):
            with self.assertRaises(RuntimeError): project.revise(self.store, 'change mid-run')
            return dict(outcome='blocked', summary='test', evidence=['test'], review_files=[])
        c.run(child, {}, run)
        self.assertEqual(c.read(self.store.path)['revision'], 1)

    def test_pause_during_plan_keeps_result_stops_before_review(self):
        def run(cfg, state, directory):
            self.store.update(lambda s: s.update(active=False))
            return p.mock_result(state)
        state = self.run_plan(run)
        self.assertEqual(state['stage'], 'spec_review')
        self.assertFalse(state['active'])
        self.assertEqual(len(state['results']), 1)

    def test_planning_artifacts_not_hidden_in_summary(self):
        state = self.run_plan()
        records = k.record_payload(state)
        plan = next(body for category, key, body in records if category == 'plans')
        self.assertIn('MOCK plan', plan)
        self.assertIn('MemberIT', plan)
        self.assertNotIn('stdout.log', plan)

class KnowledgeTests(unittest.TestCase):
    def test_static_prompt_records_have_stable_content(self):
        state = c.new_state({'title':'test'}, 'mock')
        first = [r for r in k.record_payload(state) if r[0] == 'prompts']
        second = [r for r in k.record_payload(state) if r[0] == 'prompts']
        self.assertEqual(first, second)

    @unittest.skipUnless(d.os.name == 'nt', 'Windows DPAPI')
    def test_dpapi_roundtrip_without_powershell(self):
        import ctypes
        from ctypes import wintypes
        class Blob(ctypes.Structure):
            _fields_ = [('size', wintypes.DWORD), ('data', ctypes.c_void_p)]
        plain = 'test-only-한자리-token'.encode('utf-16-le')
        buffer = ctypes.create_string_buffer(plain)
        source = Blob(len(plain), ctypes.cast(buffer, ctypes.c_void_p))
        target = Blob()
        crypt = ctypes.WinDLL('crypt32', use_last_error=True)
        crypt.CryptProtectData.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                                           ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        self.assertTrue(crypt.CryptProtectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)))
        try:
            encrypted = ctypes.string_at(target.data, target.size)
        finally:
            free = ctypes.WinDLL('kernel32').LocalFree
            free.argtypes = [ctypes.c_void_p]
            free.restype = ctypes.c_void_p
            free(target.data)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'token.dpapi'
            path.write_text(encrypted.hex(), encoding='ascii')
            self.assertEqual(d.unprotect_token(path), 'test-only-한자리-token')
            path.write_text('invalid', encoding='ascii')
            with self.assertRaises(ValueError): d.unprotect_token(path)

    def test_token_environment_override_needs_no_decryption(self):
        with patch.dict(d.os.environ, {'HANJARI_NOTION_TOKEN':'test-token'}), patch.object(d, 'unprotect_token') as native:
            self.assertEqual(d.notion_token(), 'test-token')
            native.assert_not_called()

    def test_snapshot_reads_pagination_and_nested_tables(self):
        pid = project.SOURCE_PAGE
        pages = [
            {'last_edited_time':'1', 'url':'https://notion.so/source'},
            {'results':[{'id':'a', 'type':'paragraph', 'paragraph':{'rich_text':[{'plain_text':'idea'}]}}], 'has_more':True,'next_cursor':'c'},
            {'results':[{'id':'table', 'type':'table','table':{},'has_children':True}], 'has_more':False},
            {'results':[{'id':'row','type':'table_row','table_row':{'cells':[[{'plain_text':'rule'}]]}}], 'has_more':False},
            {'last_edited_time':'1', 'url':'https://notion.so/source'}]
        with patch.object(k, 'api', side_effect=pages): result = k.snapshot(pid)
        self.assertIn('idea', result['text'])
        self.assertIn('rule', result['text'])

    def test_source_changed_or_unsupported_never_silently_truncates(self):
        with patch.object(k, 'api', side_effect=[{'last_edited_time':'1'}, {'last_edited_time':'2'}]), patch.object(k, 'children', return_value=[]):
            with self.assertRaises(ValueError): k.snapshot(project.SOURCE_PAGE)
        with patch.object(k, 'api', return_value={'last_edited_time':'1'}), patch.object(k, 'children', return_value=[{'type':'unsupported'}]):
            with self.assertRaises(ValueError): k.snapshot(project.SOURCE_PAGE)

    def test_append_reconciles_remote_after_lost_response(self):
        remote = []
        def api(*args, **kwargs):
            remote.extend(args[4]['children'])
            raise TimeoutError('response lost after write')
        with patch.object(k, 'children', side_effect=lambda page: remote), patch.object(k, 'api', side_effect=api) as write:
            with self.assertRaises(TimeoutError): k.append_record(project.SOURCE_PAGE, 'id', 'important result')
            k.append_record(project.SOURCE_PAGE, 'id', 'important result')
            self.assertEqual(write.call_count, 1)

    def test_human_edit_in_existing_record_is_preserved(self):
        remote = []
        def api(*args, **kwargs):
            remote.extend(args[4]['children']); return {}
        with patch.object(k, 'children', side_effect=lambda page: remote), patch.object(k, 'api', side_effect=api):
            k.append_record(project.SOURCE_PAGE, 'id', 'original')
            remote[0]['paragraph']['rich_text'][0]['text']['content'] += ' human note'
            with self.assertRaises(ValueError): k.append_record(project.SOURCE_PAGE, 'id', 'original')

    def test_large_record_respects_rich_text_limits(self):
        with patch.object(k, 'children', return_value=[]), patch.object(k, 'api', return_value={}) as api:
            k.append_record(project.SOURCE_PAGE, 'id', '한'*42000)
            self.assertEqual(api.call_count, 3)
            for call in api.call_args_list:
                rich = call.args[4]['children'][0]['paragraph']['rich_text']
                self.assertTrue(all(len(item['text']['content']) <= 1800 for item in rich))

    def test_sensitive_values_redacted(self):
        with patch.dict(k.os.environ, {'HANJARI_NOTION_TOKEN':'private123456'}):
            text = k.redact('private123456 ntn_abcdefghijk Bearer abcdef123')
        self.assertNotIn('private123456', text)
        self.assertNotIn('abcdefghijk', text)
        self.assertNotIn('abcdef123', text)

    def test_publication_failure_stops_at_three_without_agent_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = c.Store(root/'state')
            state = c.new_state({'task_id':'HJ-PLAN', 'title':'test'}, 'live')
            c.save(store.path, state)
            settings = {'notion':{'knowledge_pages':{'plans': project.SOURCE_PAGE}}}
            with patch.object(c, 'ROOT', root), patch.object(k, 'record_payload', return_value=[('plans','key','body')]), patch.object(k, 'append_record', side_effect=OSError()) as send:
                for _ in range(5): k.flush(store, settings)
            self.assertEqual(send.call_count, 3)
            self.assertEqual(c.read(store.path)['stage'], 'plan')
            receipt = next(iter(c.read(store.path)['knowledge_receipts'].values()))
            self.assertEqual(receipt['status'], 'needs_user')

    def test_mock_state_never_published(self):
        with tempfile.TemporaryDirectory() as directory:
            store = c.Store(directory)
            c.save(store.path, c.new_state({'title':'mock'}, 'mock'))
            with patch.object(k, 'append_record') as send:
                self.assertEqual(k.flush(store, {}), [])
                send.assert_not_called()

if __name__ == '__main__': unittest.main()
