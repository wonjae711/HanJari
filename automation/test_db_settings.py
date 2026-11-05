import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock,patch
import db_settings as db
import studio
import dashboard


@unittest.skipUnless(os.name=='nt','Windows DPAPI')
class DatabaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.patch=patch.object(db,'ROOT',self.root/'db');self.patch.start();self.addCleanup(self.patch.stop)
        self.payload=dict(url='jdbc:mysql://localhost:3306/test',username='test_user',password='test-only-비밀')

    def test_encryption_public_response_and_isolation(self):
        result=db.save('alpha',self.payload)
        raw=db.path_for('alpha').read_bytes()
        self.assertNotIn(self.payload['password'].encode(),raw)
        self.assertNotIn('password',result)
        self.assertEqual(db.load('alpha'),self.payload)
        self.assertIsNone(db.load('bravo'))
        db.save('alpha',dict(self.payload,password='',username='new_user'))
        self.assertEqual(db.load('alpha')['password'],self.payload['password'])

    def test_validation_and_corruption_fail_closed(self):
        with self.assertRaises(ValueError):db.save('alpha',dict(self.payload,password=''))
        with self.assertRaises(ValueError):db.save('../other',self.payload)
        with self.assertRaises(ValueError):db.save('alpha',dict(self.payload,url='jdbc:mysql://localhost/test?password=secret'))
        db.save('alpha',self.payload);db.path_for('alpha').write_bytes(b'corrupt')
        with self.assertRaises(ValueError):db.load('alpha')

    def test_subprocess_environment_without_global_mutation(self):
        db.save('alpha',self.payload)
        names=['APP_TEST_DB_URL','APP_TEST_DB_USERNAME','APP_TEST_DB_PASSWORD']
        source={'PATH':'original','HANJARI_TEST_DB_PASSWORD':'other-project'}
        profile={'id':'alpha','test_db_env':names}
        result=db.environment(profile,source)
        self.assertEqual(result[names[2]],self.payload['password'])
        self.assertNotIn('HANJARI_TEST_DB_PASSWORD',result)
        self.assertEqual(source,{'PATH':'original','HANJARI_TEST_DB_PASSWORD':'other-project'})

    def test_route_busy_delete_and_no_agent_execution(self):
        backend=Mock();backend.mutex=threading.Lock();backend.jobs=[]
        app=studio.Studio(backend)
        app.database(dict(self.payload,project='alpha',operation='save'))
        self.assertNotIn('password',app.database(dict(project='alpha')))
        backend.jobs=[{'status':'running'}]
        with self.assertRaises(ValueError):app.database(dict(project='alpha',operation='delete'))
        backend.jobs=[];app.database(dict(project='alpha',operation='delete'))
        self.assertFalse(db.path_for('alpha').exists());backend.submit.assert_not_called()

    def test_backend_run_injects_saved_credentials(self):
        db.save('alpha',self.payload)
        backend=dashboard.Backend(self.root/'config.json')
        profile={'id':'alpha','test_db_env':['APP_TEST_DB_URL','APP_TEST_DB_USERNAME','APP_TEST_DB_PASSWORD']}
        job=dict(id='test',project='alpha',action='run',status='running')
        with patch.object(dashboard,'ROOT',self.root),patch.object(backend,'profile',return_value=(profile,None,None)),patch.object(dashboard.subprocess,'run',return_value=Mock(returncode=0)) as run:
            backend.execute(job,['python','workspace.py'])
            self.assertEqual(run.call_args.kwargs['env']['APP_TEST_DB_PASSWORD'],self.payload['password'])
        self.assertEqual(job['status'],'completed')
