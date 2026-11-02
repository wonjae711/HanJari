import copy
import io
import tempfile
import unittest
import subprocess
from argparse import Namespace
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
import controller as c
import project
import project_planning as p
import project_profiles as profiles
import workspace as w
import knowledge as k
from verification_evidence import task_policy

PAGE = '3f8d0420-a262-8139-b1fa-c09ae31359ca'

class WorkspaceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.profile = dict(id='books', title='Books', prefix='BK', stack=profiles.STACK,
            project_dir=str(self.root/'repo'), remote='example/books', state_dir=str(self.root/'live'),
            source_page=None, record_page=PAGE, idea='책을 기록하는 서비스', constraints=[],
            baseline=copy.deepcopy(profiles.EMPTY_BASELINE), first_feature=1,
            test_db_env=['APP_TEST_DB_URL', 'APP_TEST_DB_USERNAME', 'APP_TEST_DB_PASSWORD'])
        self.store, self.config = w.setup(self.profile, {}, 'mock')
        self.addCleanup(patch.stopall)
        patch('sys.stdout', new=io.StringIO()).start()

    def plan(self):
        w.run_once(self.store, self.profile, self.config, 'mock')
        return c.read(self.store.path)

    def approved(self):
        state = self.plan()
        project.approve(self.store, state['candidate_hash'], self.config)
        return c.read(self.store.path)

    def test_one_entry_point_stops_before_development_and_does_not_repeat(self):
        state = self.plan()
        self.assertEqual([r['stage'] for r in state['results']], ['plan', 'spec_review'])
        self.assertEqual(state['stage'], 'awaiting_plan_approval')
        self.assertNotIn('handoffs', state)
        w.run_once(self.store, self.profile, self.config, 'mock')
        self.assertEqual(len(c.read(self.store.path)['results']), 2)

    def test_full_mock_flow_review_pause_and_terminal_idempotency(self):
        self.approved()
        w.run_once(self.store, self.profile, self.config, 'mock')
        child = w.current_store(self.store)
        state = c.read(child.path)
        self.assertEqual(state['stage'], 'pr_ready')
        self.assertEqual([r['stage'] for r in state['results']], ['implement', 'review', 'final_test'])
        self.assertTrue((child.directory/'review-pack.md').exists())
        w.run_once(self.store, self.profile, self.config, 'mock')
        self.assertEqual(len(c.read(child.path)['results']), 3)
        project.accept_feature(self.store, 'BK-001', 'MOCK human review')
        w.run_once(self.store, self.profile, self.config, 'mock')
        self.assertEqual(len(c.read(child.path)['results']), 3)

    def test_other_project_has_no_movie_rules_or_baseline(self):
        state = self.approved()
        self.assertNotIn('영화', str(state['task']))
        child = project.handoff(self.store, 'BK-001')
        policy = task_policy(c.read(child.path)['task'])
        self.assertEqual(set(policy['surefire-reports']), {'MemberTest'})
        self.assertNotIn('MovieApiIT', policy['failsafe-reports'])

    def test_configuration_cannot_execute_or_approve_another_project(self):
        state = self.plan()
        wrong = copy.deepcopy(self.config)
        wrong['project_dir'] = str(self.root/'other')
        with self.assertRaises(ValueError): c.run(self.store, wrong)
        with self.assertRaises(ValueError): project.approve(self.store, state['candidate_hash'], wrong)
        with self.assertRaises(ValueError): c.run(self.store, {})

    def test_two_projects_with_same_task_ids_have_isolated_states(self):
        self.approved()
        other = dict(self.profile, id='notes', project_dir=str(self.root/'other'),
                     remote='example/notes', state_dir=str(self.root/'other-state'), idea='메모장')
        store, config = w.setup(other, {}, 'mock')
        w.run_once(store, other, config, 'mock')
        a, b = c.read(self.store.path), c.read(store.path)
        self.assertNotEqual(a['id'], b['id'])
        self.assertNotEqual(a['candidate_hash'], b['candidate_hash'])
        self.assertNotIn('approved_plan', b)
        self.assertNotEqual(self.config['delivery_root'], config['delivery_root'])

    def test_live_config_origin_change_is_rejected(self):
        state = self.plan()
        state['mode'] = 'live'
        with patch.object(profiles, 'repository', return_value=(Path(self.profile['project_dir']), 'wrong/repo')):
            with self.assertRaises(ValueError): profiles.check_binding(state, self.config)

    def test_human_revision_invalidates_old_handoff_and_preserves_state(self):
        self.approved()
        child = project.handoff(self.store, 'BK-001')
        old = c.read(child.path)
        project.revise(self.store, '책에 태그 추가')
        with self.assertRaises(ValueError): project.verify_handoff(old)
        self.assertEqual(c.read(self.store.path)['revision'], 2)
        self.assertEqual(c.read(child.path), old)

    def test_pause_parent_stops_child_before_next_agent(self):
        self.approved()
        child = project.handoff(self.store, 'BK-001')
        child.update(lambda s: s.update(active=True))
        self.store.update(lambda s: s.update(pause_requested=True))
        with patch.object(c, 'execute_stage') as invoke: c.run(child, self.config)
        invoke.assert_not_called()
        self.assertFalse(c.read(child.path)['active'])

    def test_feature_suite_names_cannot_collide(self):
        state = self.plan()
        features = p.candidate(state)['features']
        second = copy.deepcopy(features[0]); second['task_id'] = 'BK-002'
        with self.assertRaises(ValueError): p.validate_features(features + [second], state['task'])

    def test_notion_product_records_separate_automation_home(self):
        state = self.plan()
        state['mode'] = 'live'
        c.save(self.store.path, state)
        product_page = self.profile['record_page']
        home_page = self.config['automation_pages']['workflow']
        with patch.object(k, 'record_payload', return_value=[('plans','plan','product'), ('workflow','guide','automation')]), patch.object(k, 'append_record') as send:
            k.flush(self.store, self.config['integrations'], self.config)
        self.assertEqual([call.args[0] for call in send.call_args_list], [product_page, home_page])

    def test_source_output_same_page_rejected(self):
        with self.assertRaises(ValueError): profiles.validate(dict(self.profile, source_page=PAGE))
        with self.assertRaises(ValueError): profiles.validate(dict(self.profile, source_page=PAGE.replace('-', '').upper()))

    def test_remote_urls_cannot_embed_credentials(self):
        self.assertEqual(profiles.remote_name('git@github.com:Owner/Repo.git'), 'owner/repo')
        self.assertEqual(profiles.remote_name('https://github.com/Owner/Repo.git'), 'owner/repo')
        with self.assertRaises(ValueError): profiles.remote_name('https://token@github.com/Owner/Repo.git')

    def test_missing_activation_never_initializes_or_invokes(self):
        config_path = self.root/'config.json'; c.save(config_path, {})
        with patch.object(w, 'load_profile', return_value=self.profile), patch.object(c, 'invoke') as invoke:
            with self.assertRaises(ValueError): w.main(['run', '--config', str(config_path), '--mode', 'mock'])
        invoke.assert_not_called()
        self.assertFalse(self.store.path.exists())

    def test_register_real_empty_repo_preserves_existing_profile(self):
        repo = self.root/'empty'; repo.mkdir()
        subprocess.run(['git', 'init', '-b', 'main', str(repo)], check=True, capture_output=True)
        subprocess.run(['git', '-C', str(repo), 'remote', 'add', 'origin', 'https://github.com/example/books.git'], check=True, capture_output=True)
        args = Namespace(project='books', repo=str(repo), idea_file=None, idea='book idea',
                         title='Books', baseline=None, prefix='BK', source_page=None, record_page=PAGE)
        registry = self.root/'registry'
        saved = w.register(args, registry)
        loaded = w.load_profile('books', {}, registry)
        self.assertEqual(saved, loaded)
        with self.assertRaises(ValueError): w.register(args, registry)
        self.assertEqual(loaded, w.load_profile('books', {}, registry))
        self.assertEqual(profiles.git(repo, 'branch', '--show-current'), 'main')
        self.assertFalse(Path(saved['state_dir']).exists())

    def test_live_branch_preparation_supports_unborn_main_and_pins_branch(self):
        self.approved()
        child = project.handoff(self.store, 'BK-001')
        child.update(lambda s: s.update(mode='live'))
        repo = Path(self.profile['project_dir']); repo.mkdir(exist_ok=True)
        subprocess.run(['git', 'init', '-b', 'main', str(repo)], check=True, capture_output=True)
        w.prepare_branch(child, self.config)
        branch = profiles.git(repo, 'branch', '--show-current')
        self.assertEqual(branch, 'codex/books-bk-001')
        w.prepare_branch(child, self.config)
        subprocess.run(['git', '-C', str(repo), 'checkout', '-b', 'other'], check=True, capture_output=True)
        with self.assertRaises(ValueError): w.prepare_branch(child, self.config)

if __name__ == '__main__': unittest.main()
