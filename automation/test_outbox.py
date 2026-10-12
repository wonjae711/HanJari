import tempfile
import unittest
from pathlib import Path
import controller as c
import outbox as o

class OutboxTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = c.Store(Path(self.temp.name))
        self.state = c.new_state({'title': 'test', 'task_id': 'HJ-001'}, 'live')
        c.save(self.store.path, self.state)

    def test_state_and_event_saved_together_without_duplicate(self):
        self.store.update(lambda s: s.update(stage='pr_ready'))
        first = c.read(self.store.path)
        self.assertEqual(len(o.pending(first, 'notification')), 1)
        self.store.update(lambda s: None)
        second = c.read(self.store.path)
        self.assertEqual(first['outbox'], second['outbox'])
        self.assertEqual(second['stage'], 'pr_ready')

    def test_delivery_failure_does_not_change_workflow_and_stops_at_three(self):
        s = self.store.update(lambda s: s.update(stage='pr_ready'))
        event_id = s['outbox'][0]['id']
        for _ in range(4):
            self.store.update(lambda s: o.receipt(s, event_id, 'notion', False, c.now()))
        s = c.read(self.store.path)
        delivery = s['outbox'][0]['channels']['notion']
        self.assertEqual(delivery['attempts'], 3)
        self.assertEqual(delivery['status'], 'needs_user')
        self.assertEqual(s['stage'], 'pr_ready')
        self.assertFalse(s['active'])
        self.assertEqual(len(s['outbox']), 1)
        self.assertEqual(len(o.pending(s, 'notification')), 1)

    def test_receipt_is_idempotent_and_channels_independent(self):
        s = self.store.update(lambda s: s.update(stage='pr_ready'))
        event_id = s['outbox'][0]['id']
        for _ in range(2):
            self.store.update(lambda s: o.receipt(s, event_id, 'notion', True, c.now()))
        s = c.read(self.store.path)
        self.assertEqual(o.pending(s, 'notion'), [])
        self.assertEqual(len(o.pending(s, 'notification')), 1)
        self.assertEqual(s['outbox'][0]['channels']['notion']['attempts'], 1)

    def test_mock_and_running_do_not_notify(self):
        s = self.store.update(lambda s: s.update(mode='mock'))
        self.assertNotIn('outbox', s)
        s = self.store.update(lambda s: s.update(mode='live', running={'id': 'x'}, active=True))
        self.assertEqual(len(o.pending(s, 'notion')), 1)
        self.assertEqual(o.pending(s, 'notification'), [])

    def test_error_is_not_exported(self):
        s = self.store.update(lambda s: c.event(s, 'attention', 'password=secret token=secret'))
        self.assertEqual(len(o.pending(s, 'notification')), 1)
        self.assertNotIn('secret', str(s['outbox']))

    def test_retry_requires_reason_and_preserves_history(self):
        s = self.store.update(lambda s: s.update(stage='pr_ready'))
        event_id = s['outbox'][0]['id']
        for _ in range(3): o.receipt(s, event_id, 'notion', False, c.now())
        with self.assertRaises(ValueError): o.retry(s, event_id, 'notion', '')
        o.retry(s, event_id, 'notion', 'connection restored')
        delivery = o.pending(s, 'notion')[0]['channels']['notion']
        self.assertEqual(delivery['attempts'], 0)
        self.assertEqual(delivery['history'][0]['attempts'], 3)

if __name__ == '__main__': unittest.main()
