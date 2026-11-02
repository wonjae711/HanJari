import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock
import controller as c
import delivery as d
import outbox as o

class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.store = c.Store(root/'state')
        self.delivery_root = root/'delivery'
        state = c.new_state({'title': 'test', 'task_id': 'HJ-001'}, 'live')
        state.update(stage='pr_ready')
        c.save(self.store.path, state)
        self.store.update(lambda s: None)
        self.settings = {'notion': {'sync_mode': 'direct-api', 'tasks': {'HJ-001': '3f5d0420-a262-81c2-80b9-d349ca4596e5'}}}

    def flush(self, senders):
        return d.flush(self.store, self.settings, self.delivery_root, senders)

    def test_repeated_flush_does_not_send_twice(self):
        senders = {'notion': Mock(), 'notification': Mock()}
        self.flush(senders)
        self.flush(senders)
        for sender in senders.values(): sender.assert_called_once()

    def test_failure_is_independent_and_bounded(self):
        senders = {'notion': Mock(side_effect=RuntimeError('secret')), 'notification': Mock()}
        for _ in range(4): self.flush(senders)
        self.assertEqual(senders['notion'].call_count, 3)
        senders['notification'].assert_called_once()
        state = c.read(self.store.path)
        self.assertEqual(state['stage'], 'pr_ready')
        self.assertFalse(state['active'])
        self.assertNotIn('secret', str(state['outbox']))

    def test_missing_token_does_not_consume_retry(self):
        senders = {'notion': Mock(side_effect=d.NotConfigured()), 'notification': Mock()}
        self.flush(senders)
        item = o.pending(c.read(self.store.path), 'notion')[0]
        self.assertEqual(item['channels']['notion']['attempts'], 0)

    def test_coalesces_projection_and_cancels_obsolete_notification(self):
        self.store.update(lambda s: s.update(stage='implement', active=True))
        senders = {'notion': Mock(), 'notification': Mock()}
        self.flush(senders)
        senders['notion'].assert_called_once()
        self.assertEqual(senders['notion'].call_args.args[0]['snapshot']['stage'], 'implement')
        senders['notification'].assert_not_called()

    def test_older_state_dir_cannot_overwrite_newer_delivered_projection(self):
        senders = {'notion': Mock(), 'notification': Mock()}
        first_state = c.read(self.store.path)
        self.store.update(lambda s: s.update(stage='needs_user'))
        self.flush(senders)
        other = c.Store(Path(self.tmp.name)/'older-state')
        c.save(other.path, first_state)
        d.flush(other, self.settings, self.delivery_root, senders)
        self.assertEqual(senders['notion'].call_count, 1)
        self.assertEqual(senders['notification'].call_count, 1)

    def test_notion_patch_is_scoped_and_checks_revision(self):
        item = c.read(self.store.path)['outbox'][0]
        expected = d.notion_properties(item['snapshot'])
        properties = {name: {'type': next(iter(value)), **value} for name, value in expected.items()}
        properties.update({'업무 ID': {'rich_text': [{'plain_text': 'HJ-001'}]}, '요구사항 버전': {'number': 1}})
        page_id = self.settings['notion']['tasks']['HJ-001']
        with patch.dict(os.environ, {'HANJARI_NOTION_TOKEN': 'test-token'}), patch.object(d, 'notion_request', side_effect=[{'properties': properties}, {'id': page_id}]) as api:
            d.send_notion(item, self.settings)
            self.assertEqual(api.call_args.args[3], {'properties': expected})
            self.assertNotIn('진행 메모', expected)
        properties['요구사항 버전']['number'] = 2
        with patch.dict(os.environ, {'HANJARI_NOTION_TOKEN': 'test-token'}), patch.object(d, 'notion_request', return_value={'properties': properties}) as api:
            with self.assertRaises(ValueError): d.send_notion(item, self.settings)
            api.assert_called_once()

    def test_human_completed_page_is_not_reopened(self):
        item = c.read(self.store.path)['outbox'][0]
        props = {'업무 ID': {'rich_text': [{'plain_text': 'HJ-001'}]}, '요구사항 버전': {'number': 1}, '진행 상태': {'select': {'name': '완료'}}}
        with patch.dict(os.environ, {'HANJARI_NOTION_TOKEN': 'test-token'}), patch.object(d, 'notion_request', return_value={'properties': props}) as api:
            with self.assertRaises(ValueError): d.send_notion(item, self.settings)
            api.assert_called_once()

    def test_integration_token_not_passed_to_agents(self):
        with patch.dict(os.environ, {'HANJARI_NOTION_TOKEN': 'test-token', 'HANJARI_TEST_DB_PASSWORD': 'test-db'}):
            env = c.child_environment({})
            self.assertNotIn('HANJARI_NOTION_TOKEN', env)
            self.assertEqual(env['HANJARI_TEST_DB_PASSWORD'], 'test-db')

    def test_transport_failure_does_not_restart_agent(self):
        self.store.update(lambda s: s.update(stage='final_test', active=True))
        def agent(*args):
            return {'outcome': 'pass', 'summary': 'test', 'evidence': ['test'], 'review_files': []}
        with patch.object(d, 'flush', side_effect=RuntimeError('network')), patch.object(d.c, 'read', wraps=c.read), patch.object(d, 'ROOT', Path(self.tmp.name)):
            c.save(Path(self.tmp.name)/'integrations.json', {})
            fake = Mock(side_effect=agent)
            c.run(self.store, {'integration_delivery': True}, runner=fake)
            fake.assert_called_once()
        state = c.read(self.store.path)
        self.assertEqual(state['stage'], 'pr_ready')
        self.assertFalse(state['active'])

if __name__ == '__main__': unittest.main()
