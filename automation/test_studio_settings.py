import tempfile
from pathlib import Path
from unittest.mock import Mock,patch
import unittest
import studio


class SettingsTests(unittest.TestCase):
    def test_catalog_returns_paths_and_presence_not_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'.env').write_text('PASSWORD=do-not-expose',encoding='utf-8')
            resources=root/'src/main/resources';resources.mkdir(parents=True)
            (resources/'application-dev.yml').write_text('password: do-not-expose',encoding='utf-8')
            backend=Mock();backend.profile.return_value=({'id':'alpha','project_dir':str(root)},None,None)
            with patch.object(studio.db_settings,'public',return_value={'saved':True}):
                result=studio.Studio(backend).settings({'project':'alpha'})
            files={f['path']:f for f in result['files']}
            self.assertTrue(files['.env']['exists'])
            self.assertFalse(files['.env.example']['exists'])
            self.assertTrue(files['src/main/resources/application-dev.yml']['exists'])
            self.assertNotIn('do-not-expose',str(result));backend.submit.assert_not_called()

    def test_unreadable_database_does_not_hide_file_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            backend=Mock();backend.profile.return_value=({'id':'alpha','project_dir':directory},None,None)
            with patch.object(studio.db_settings,'public',side_effect=ValueError('private error')):
                result=studio.Studio(backend).settings({'project':'alpha'})
            self.assertTrue(result['database_error']);self.assertTrue(result['files'])
            self.assertNotIn('private error',str(result))
