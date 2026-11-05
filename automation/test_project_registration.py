import tempfile
import threading
import tkinter as tk
import unittest
from pathlib import Path
from unittest.mock import Mock,patch
import project_registration as r
import studio_view
from test_desktop import state


class RegistrationTests(unittest.TestCase):
    def values(self):
        return dict(project='bookshelf',title='책장',repo='C:/projects/Bookshelf',idea='독서 기록',
                    record_page='https://www.notion.so/Books-123456781234123412341234567890ab?pvs=4',
                    source_page='',prefix='BK',baseline='')

    def test_page_link_and_invalid_host(self):
        self.assertEqual(r.page_id(self.values()['record_page']),'12345678-1234-1234-1234-1234567890ab')
        with self.assertRaises(ValueError):r.page_id('https://example.com/123456781234123412341234567890ab')

    def test_missing_inputs_never_prompt_or_register(self):
        with patch.object(r.workspace,'register') as register,patch('builtins.input',side_effect=AssertionError):
            values=self.values();values['idea']=' '
            with self.assertRaises(ValueError):r.register_project(values)
            register.assert_not_called()

    def test_registration_preserves_duplicate_and_does_not_start(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);repo=base/'repo';repo.mkdir();registry=base/'registry'
            with patch.object(r.workspace.profiles,'repository',return_value=(repo,'example/bookshelf')):
                profile=r.register_project(self.values(),registry)
                saved=registry/'bookshelf'/'profile.json';before=saved.read_bytes()
                self.assertEqual(profile['title'],'책장');self.assertFalse(Path(profile['state_dir']).exists())
                with self.assertRaises(ValueError):r.register_project(self.values(),registry)
                self.assertEqual(saved.read_bytes(),before)

    def test_same_source_and_output_rejected_without_files(self):
        with tempfile.TemporaryDirectory() as directory:
            base=Path(directory);values=self.values();values['source_page']=values['record_page']
            with patch.object(r.workspace.profiles,'repository',return_value=(base,'example/bookshelf')):
                with self.assertRaises(ValueError):r.register_project(values,base/'registry')
            self.assertFalse((base/'registry').exists())

    def test_dialog_opens_without_backend_actions(self):
        root=tk.Tk();root.withdraw();self.addCleanup(root.destroy)
        backend=Mock();backend.mutex=threading.Lock();backend.jobs=[]
        app=studio_view.Studio(root,backend,poll=False);app.render(state())
        app.add_project();root.update_idletasks()
        self.assertEqual(len(app.project_dialog.fields),7)
        backend.submit.assert_not_called()
        app.project_dialog.close()

    def test_success_selects_new_project_without_running_it(self):
        root=tk.Tk();root.withdraw();self.addCleanup(root.destroy)
        backend=Mock();backend.mutex=threading.Lock();backend.jobs=[]
        app=studio_view.Studio(root,backend,poll=False);app.render(state())
        app.project_keys=['hanjari'];app.selector.configure(values=['영화 예매 MVP']);app.selector.current(0)
        app.add_project();dialog=app.project_dialog
        dialog.results.put((True,{'id':'bookshelf','title':'책장'}))
        with patch.object(app,'change_project') as change,patch.object(r.messagebox,'showinfo'):
            dialog.poll();change.assert_called_once()
        self.assertEqual(app.project_keys[app.selector.current()],'bookshelf')
        backend.submit.assert_not_called()
        app.render(dict(project={'id':'bookshelf','title':'책장'},mode='live',usage=None,
                        stage='not_started',results=[],events=[],features=[],document=''))
        self.assertIn('시작',app.hero_title.cget('text'))
