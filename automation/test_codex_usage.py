import io
import json
import unittest
from unittest.mock import Mock,patch
import codex_usage as u


class UsageTests(unittest.TestCase):
    def test_windows_and_missing_values(self):
        result=u.normalize({'rateLimits':{'primary':{'usedPercent':81,'windowDurationMins':300,'resetsAt':123},'secondary':None}})
        self.assertEqual(result['buckets'][0]['windows'][0]['used'],81)
        self.assertEqual(result['buckets'][0]['windows'][0]['minutes'],300)
        self.assertEqual(u.normalize({'rateLimits':{'primary':{'usedPercent':True}}})['buckets'],[])
        self.assertEqual(u.normalize({})['buckets'],[])

    def test_cache_and_failed_refresh_keeps_timestamp(self):
        reader=u.Reader()
        value={'buckets':[],'checked_at':123}
        with patch.object(u,'fetch',return_value=value) as fetch,patch.object(u.time,'monotonic',return_value=100):
            self.assertIsNone(reader.get('codex')['error']);reader.get('codex');fetch.assert_called_once()
        with patch.object(u,'fetch',side_effect=ValueError('internal')),patch.object(u.time,'monotonic',return_value=161):
            result=reader.get('codex')
            self.assertEqual(result['checked_at'],123);self.assertTrue(result['error'])
            self.assertNotIn('internal',result['error'])

    def test_protocol_reads_only_and_closes_own_process(self):
        class Sink(io.StringIO):
            def close(self):self.saved=self.getvalue();super().close()
        sink=Sink();proc=Mock(stdin=sink,stdout=io.StringIO('{"id":1,"result":{}}\n{"id":2,"result":{"rateLimits":null}}\n'))
        with patch.object(u.shutil,'which',return_value='codex.exe'),patch.object(u.subprocess,'Popen',return_value=proc):
            self.assertEqual(u.fetch()['buckets'],[])
        methods=[json.loads(line)['method'] for line in sink.saved.splitlines()]
        self.assertEqual(methods,['initialize','initialized','account/rateLimits/read'])
        proc.wait.assert_called_once();self.assertTrue(proc.stdout.closed)
