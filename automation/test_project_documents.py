import copy
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
import controller as c
import project_planning as p
import project_documents as d
import knowledge as k

PID = '3f4d0420-a262-8139-85ac-c8fb6aa13ea6'
DS = 'e8e30a99-a638-4c4f-9b5a-ef8f5ff97b26'

class Remote:
    def __init__(self):
        self.page = {'properties': {'문서': {'title': d.rich('프로젝트 개요')},
                                   '상태': {'type': 'select', 'select': {'name': '작성 전'}}},
                     'parent': {'data_source_id': DS}, 'last_edited_time': 'fixed', 'url': 'https://notion.so/'+PID}
        self.nodes = {PID: [{'id':'human', 'type':'paragraph', 'paragraph':{'rich_text': d.rich('원래 요구사항')}}]}
        self.writes = 0
        self.lose_response = False
    def children(self, identity): return copy.deepcopy(self.nodes.get(identity, []))
    def api(self, resource, identity, suffix='', method='GET', payload=None, query=None):
        if resource == 'pages':
            if method == 'PATCH':
                for name, value in payload['properties'].items():
                    self.page['properties'][name].update(copy.deepcopy(value))
            return copy.deepcopy(self.page)
        if resource == 'blocks' and method == 'PATCH':
            self.writes += 1
            for raw in payload['children']:
                block = copy.deepcopy(raw); block['id'] = str(uuid.uuid4())
                children = block[block['type']].pop('children', [])
                self.nodes[block['id']] = children
                block['has_children'] = bool(children)
                self.nodes.setdefault(identity, []).append(block)
            if self.lose_response:
                self.lose_response = False
                raise TimeoutError('response lost after server stored content')
            return {}
        raise AssertionError((resource,method))

def plan():
    state = c.new_state({'kind':'project_plan', 'title':'test', 'task_id':'HJ-PLAN',
                         'confirmed_constraints':['사용자 확정'], 'steering':['로컬 검증 전용']}, 'live')
    result = p.mock_result(state)
    result['artifact']['document'] = '\n\n'.join(['## 목표와 범위\n목표', '## 기술 제안\n기술',
        '## 사용자 흐름\n흐름', '## 데이터 모델\nmembers(id)', '## API 계약\nGET /movies', '## 검증\n인수 조건'])
    state['results'] = [dict(stage='plan', revision=1, **result)]
    return state

class DocumentTests(unittest.TestCase):
    def setUp(self):
        self.remote = Remote()
        self.settings = {'data_source_id':DS, 'title_property':'문서', 'status_property':'상태',
                         'pages':{'overview': {'page_id':PID,'title':'프로젝트 개요'}}}
        self.target = self.settings['pages']['overview']
        self.payload = d.documents(plan())['overview']
        self.patches = [patch.object(k,'api',self.remote.api), patch.object(k,'children',self.remote.children)]
        for item in self.patches: item.start(); self.addCleanup(item.stop)

    def publish(self): return d.publish('overview', self.payload, self.target, self.settings, 'state')

    def test_projects_routed_into_seven_documents_not_experiments(self):
        rendered = d.documents(plan())
        self.assertEqual(set(rendered), d.ROLES)
        self.assertIn('members(id)',rendered['erd']['body'])
        self.assertNotIn('GET /movies',rendered['erd']['body'])
        self.assertIn('GET /movies',rendered['api']['body'])
        self.assertIn('로컬 검증 전용',rendered['decisions']['body'])
        self.assertTrue(all(r['status']=='초안' for r in rendered.values()))

    def test_whole_plan_approval_is_not_document_or_implementation_completion(self):
        s=plan(); s['approved_plan']={'hash':p.plan_hash(s)}
        self.assertTrue(all(r['status']=='작성 중' for r in d.documents(s).values()))

    def test_current_revision_only_and_latest_draft(self):
        s=plan(); s['revision']=2
        self.assertEqual(d.documents(s),{})
        s['results'].append(dict(s['results'][0],revision=2))
        self.assertIn('r2',d.documents(s)['overview']['body'])

    def test_publish_preserves_original_blocks_and_is_idempotent(self):
        original=copy.deepcopy(self.remote.nodes[PID][0])
        self.publish(); self.publish()
        self.assertEqual(self.remote.writes,1)
        self.assertEqual(self.remote.nodes[PID][0],original)
        self.assertEqual(self.remote.page['properties']['상태']['select']['name'],'초안')

    def test_lost_response_reconciles_without_duplicate(self):
        self.remote.lose_response=True
        with self.assertRaises(TimeoutError):self.publish()
        self.publish()
        self.assertEqual(self.remote.writes,1)

    def test_human_edit_in_projection_is_not_overwritten(self):
        self.publish()
        root=self.remote.nodes[PID][-1]
        self.remote.nodes[root['id']][0]['paragraph']['rich_text']=d.rich('사람이 수정함')
        with self.assertRaises(ValueError):self.publish()
        self.assertEqual(self.remote.writes,1)

    def test_human_completed_document_is_not_reopened(self):
        self.remote.page['properties']['상태']['select']['name']='완료'
        with self.assertRaises(ValueError):self.publish()
        self.assertEqual(self.remote.writes,0)

    def test_wrong_title_or_database_never_writes(self):
        self.remote.page['parent']['data_source_id']='wrong'
        with self.assertRaises(ValueError):self.publish()
        self.remote.page['parent']['data_source_id']=DS
        self.remote.page['properties']['문서']['title']=d.rich('다른 문서')
        with self.assertRaises(ValueError):self.publish()
        self.assertEqual(self.remote.writes,0)

    def test_projection_does_not_change_source_but_human_edit_does(self):
        before=k.snapshot(PID)
        self.publish()
        self.assertEqual(k.snapshot(PID)['sha256'],before['sha256'])
        self.remote.nodes[PID][0]['paragraph']['rich_text']=d.rich('변경된 요구사항')
        self.assertNotEqual(k.snapshot(PID)['sha256'],before['sha256'])

    def test_large_docs_fit_block_and_rich_text_limits(self):
        result=d.blocks(('## 절\n\n'+'긴 문서'*1000+'\n\n')*30)
        self.assertLessEqual(len(result),90)
        for b in result:
            self.assertTrue(all(len(t['text']['content'])<=1800 for t in b[b['type']]['rich_text']))

    def test_three_failures_stop_and_keep_model_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store=c.Store(directory); s=plan(); c.save(store.path,s)
            with patch.object(d,'documents',return_value={'overview':self.payload}),patch.object(d,'publish',side_effect=OSError()) as send:
                for _ in range(5):d.flush(store,{'notion':{'project_documents':self.settings}})
            self.assertEqual(send.call_count,3)
            saved=c.read(store.path)
            self.assertEqual(saved['stage'],s['stage'])
            self.assertEqual(saved['results'],s['results'])
            self.assertEqual(next(iter(saved['document_receipts'].values()))['status'],'needs_user')

    def test_mock_and_feature_states_never_project(self):
        with tempfile.TemporaryDirectory() as directory:
            store=c.Store(directory)
            for s in [dict(plan(),mode='mock'), c.new_state({'title':'feature'},'live')]:
                c.save(store.path,s)
                self.assertEqual(d.flush(store,{'notion':{'project_documents':self.settings}}),[])
        self.assertEqual(self.remote.writes,0)

if __name__=='__main__':unittest.main()
