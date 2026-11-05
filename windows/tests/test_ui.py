from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from PySide6.QtCore import Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QLineEdit
from studio.app import Studio, application
from studio.core import Store, StudioError, write_json


class DesktopUITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qt = application()

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.folder.name))
        self.store.save_settings({'language': 'zh-CN'})
        self.ui = Studio(self.store)
        self.ui.resize(1320, 840)
        self.ui.show()
        self.qt.processEvents()

    def tearDown(self):
        self.ui.cancel_event.set()
        self.wait(lambda: not self.ui.busy)
        self.ui.close()
        self.qt.processEvents()
        self.folder.cleanup()

    def wait(self, condition, timeout=5):
        deadline = time.monotonic() + timeout
        while not condition() and time.monotonic() < deadline:
            QTest.qWait(10)
        self.assertTrue(condition(), 'GUI operation did not finish')

    def audio(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), 'wb') as a:
            a.setparams((1, 2, 24000, 0, 'NONE', 'not compressed'))
            a.writeframes(b'\0\0' * 24000)
        return path

    def test_language_switch_preserves_drafts_and_voice_selection(self):
        record = {'id': uuid.uuid4().hex, 'name': '我的声音', 'created': time.time(),
                  'duration': 5., 'prompt': 'prompt.json', 'transcript': '原文'}
        write_json(self.store.record_path('voices', record['id'])/'record.json', record)
        self.ui.refresh_records()
        self.ui.voice_choice.setCurrentIndex(1)
        self.ui.text_editor.setPlainText('My text stays. 我的文案。')
        self.ui.sample_editor.setPlainText('自然的云端文案。')
        self.ui.transcript_editor.setPlainText('音频里的原话。')
        self.ui.secret.setText('test-draft-value')
        self.ui.speech_language.setCurrentIndex(self.ui.speech_language.findData('ja'))
        self.ui.navigate(4)
        self.ui.ui_language.setCurrentIndex(self.ui.ui_language.findData('en'))
        self.qt.processEvents()
        self.assertEqual('My text stays. 我的文案。', self.ui.text_editor.toPlainText())
        self.assertEqual('自然的云端文案。', self.ui.sample_editor.toPlainText())
        self.assertEqual('音频里的原话。', self.ui.transcript_editor.toPlainText())
        self.assertEqual('test-draft-value', self.ui.secret.text())
        self.assertEqual(record['id'], self.ui.voice_choice.currentData())
        self.assertEqual('ja', self.ui.speech_language.currentData())
        self.assertEqual(4, self.ui.pages.currentIndex())
        self.assertEqual('en', self.store.settings()['language'])

    def test_cloud_configuration_uses_user_supplied_fields(self):
        self.ui.navigate(4)
        self.ui.cloud_endpoint.setText("https://example.com/v1/audio/speech")
        self.ui.cloud_model.setText("test-model")
        self.ui.cloud_voice.setText("test-voice")
        self.ui.save_settings()
        self.assertEqual("test-voice", self.store.settings()["cloud_voice"])
        self.ui.navigate(2)
        self.assertEqual(2, self.ui.pages.currentIndex())

    def test_generate_finishes_and_updates_preview_history(self):
        record = {'id': uuid.uuid4().hex, 'voice': '测试音色', 'text': '测试配音。',
                  'language': 'zh', 'created': time.time()}
        def synthesize(*args):
            args[-1]('1/1')
            self.audio(self.store.record_path('history', record['id'])/'audio.wav')
            write_json(self.store.record_path('history', record['id'])/'record.json', record)
            return record
        with patch.object(self.ui.engine, 'synthesize', side_effect=synthesize) as engine:
            QTest.mouseClick(self.ui.generate_button, Qt.MouseButton.LeftButton)
            self.wait(lambda: not self.ui.busy)
            engine.assert_called_once()
        self.assertTrue(self.ui.preview.is_file())
        self.assertTrue(self.ui.play_button.isEnabled())
        self.assertEqual(1, self.ui.history_list.rowCount())
        self.assertTrue(self.ui.generate_button.isEnabled())

    def test_local_job_keeps_credentials_editable_and_cancels(self):
        started = threading.Event()
        def task(progress):
            started.set()
            self.ui.cancel_event.wait(4)
            raise StudioError('cancelled')
        self.ui.start(task, lambda _: None)
        self.wait(started.is_set)
        self.ui.navigate(4)
        self.qt.processEvents()
        self.assertTrue(self.ui.secret.isEnabled())
        QTest.keyClicks(self.ui.secret, 'test-only')
        self.assertEqual('test-only', self.ui.secret.text())
        self.assertFalse(self.ui.close())
        QTest.mouseClick(self.ui.cancel_button, Qt.MouseButton.LeftButton)
        self.wait(lambda: not self.ui.busy)
        self.assertTrue(self.ui.notice.isVisible())
        self.assertTrue(self.ui.ui_language.isEnabled())

    def test_failed_secret_save_preserves_input(self):
        self.ui.cloud_endpoint.setText('https://example.com/tts')
        self.ui.secret.setText('test-unsaved-key')
        with patch.object(self.store, 'save_secret', side_effect=StudioError('unexpected')):
            self.ui.save_settings()
        self.assertEqual('test-unsaved-key', self.ui.secret.text())
        self.assertFalse(self.store.credential_path('https://example.com/tts').exists())

    def test_cloud_result_only_plays_audio(self):
        audio = self.audio(self.store.root/'cloud'/'sample.wav')
        self.ui.sample_ready(audio)
        with patch.object(self.ui, 'play_file') as playback:
            self.ui.play_sample()
            playback.assert_called_once_with(audio)
        self.assertFalse(hasattr(self.ui, "clone_sample"))

    def test_editing_endpoint_clears_draft_key_without_calling_provider(self):
        with patch.object(self.ui.cloud, 'sample') as provider:
            self.ui.navigate(4)
            self.ui.cloud_endpoint.setText("https://example.com/tts")
            self.ui.secret.setText("test-draft")
            self.ui.cloud_endpoint.setText("https://other.example.com/tts")
            self.assertEqual("", self.ui.secret.text())
            provider.assert_not_called()
        self.assertEqual(QLineEdit.EchoMode.Password, self.ui.secret.echoMode())

    def test_export_preview_copies_audio(self):
        self.ui.preview = self.audio(self.store.root/'work'/'preview.wav')
        target = self.store.root/'export.wav'
        with patch('studio.desktop.QFileDialog.getSaveFileName', return_value=(str(target), 'WAV')):
            self.ui.export_preview()
        self.assertEqual(self.ui.preview.read_bytes(), target.read_bytes())

    def test_main_controls_fit_window_sizes(self):
        for width, height in [(1000, 700), (1320, 840), (1920, 1080), (2560, 1392)]:
            self.ui.resize(width, height)
            self.ui.navigate(0)
            self.qt.processEvents()
            for widget in [self.ui.generate_button, self.ui.voice_choice, self.ui.speech_language]:
                point = widget.mapTo(self.ui, widget.rect().bottomRight())
                self.assertLess(point.x(), self.ui.width(), (width, widget.objectName(), point))
                self.assertLess(point.y(), self.ui.height(), (height, widget.objectName(), point))

    def test_kokoro_draft_and_voice_survive_language_switch(self):
        self.ui.mode.setCurrentIndex(self.ui.mode.findData("kokoro"))
        self.ui.text_editor.setPlainText('中文文案保留。')
        self.ui.voice_choice.setCurrentIndex(self.ui.voice_choice.findData(58))
        self.ui.light_speed.setCurrentIndex(self.ui.light_speed.findData(1.25))
        self.ui.ui_language.setCurrentIndex(self.ui.ui_language.findData('en'))
        self.qt.processEvents()
        self.assertEqual(0,self.ui.pages.currentIndex())
        self.assertEqual("kokoro",self.ui.mode.currentData())
        self.assertEqual('中文文案保留。',self.ui.text_editor.toPlainText())
        self.assertEqual(58,self.ui.voice_choice.currentData())
        self.assertEqual(1.25,self.ui.light_speed.currentData())
        self.assertEqual(58,self.store.settings()['kokoro_voice'])
        self.assertEqual(1.25,self.store.settings()['kokoro_speed'])

    def test_kokoro_routes_generation_to_its_engine_and_shared_history(self):
        record={'id':uuid.uuid4().hex,'voice':'Kokoro · 中文女声 001','text':'你好',
                'language':'zh','created':time.time(),'engine':'kokoro'}
        def generate(*args):
            self.audio(self.store.record_path('history',record['id'])/'audio.wav')
            write_json(self.store.record_path('history',record['id'])/'record.json',record)
            return record
        with patch('studio.light.KokoroEngine.synthesize',side_effect=generate) as kokoro, patch.object(self.ui.engine,'synthesize') as qwen:
            self.ui.mode.setCurrentIndex(self.ui.mode.findData("kokoro"));QTest.mouseClick(self.ui.generate_button, Qt.MouseButton.LeftButton);self.wait(lambda:not self.ui.busy)
            kokoro.assert_called_once();qwen.assert_not_called()
        self.assertTrue(self.ui.preview.is_file())
        self.assertTrue(self.ui.play_button.isEnabled())
        self.assertEqual(1,self.ui.history_list.rowCount())

    def test_models_share_one_studio_and_preserve_script_and_voice(self):
        self.assertEqual(5,self.ui.pages.count())
        self.assertEqual(5,len(self.ui.nav_buttons))
        self.ui.text_editor.setPlainText("切换模型也保留这段文案。")
        self.ui.mode.setCurrentIndex(self.ui.mode.findData("kokoro"))
        self.qt.processEvents()
        self.assertEqual(103,self.ui.voice_choice.count())
        self.ui.voice_choice.setCurrentIndex(self.ui.voice_choice.findData(58))
        self.assertTrue(self.ui.light_speed.isVisible())
        self.assertFalse(self.ui.speech_language.isVisible())
        self.ui.mode.setCurrentIndex(self.ui.mode.findData("base"))
        self.qt.processEvents()
        self.assertFalse(self.ui.light_speed.isVisible())
        self.assertTrue(self.ui.speech_language.isVisible())
        self.ui.mode.setCurrentIndex(self.ui.mode.findData("kokoro"))
        self.assertEqual(58,self.ui.voice_choice.currentData())
        self.assertEqual("切换模型也保留这段文案。",self.ui.text_editor.toPlainText())
        self.assertEqual(0,self.ui.pages.currentIndex())

    def test_paradee_uses_shared_studio_and_english_example(self):
        self.ui.mode.setCurrentIndex(self.ui.mode.findData('paradee'))
        self.qt.processEvents()
        self.assertEqual(1,self.ui.voice_choice.count())
        self.assertEqual(0,self.ui.voice_choice.currentData())
        QTest.mouseClick(self.ui.paradee_example,Qt.MouseButton.LeftButton)
        self.assertTrue(self.ui.text_editor.toPlainText().startswith('Hello'))
        record={'id':uuid.uuid4().hex,'voice':'Paradee · af_heart','text':'Hello',
                'language':'en','created':time.time(),'engine':'paradee'}
        def generate(*args):
            self.audio(self.store.record_path('history',record['id'])/'audio.wav')
            write_json(self.store.record_path('history',record['id'])/'record.json',record)
            return record
        with patch('studio.light.KokoroEngine.synthesize',side_effect=generate) as tiny,patch.object(self.ui.engine,'synthesize') as qwen:
            QTest.mouseClick(self.ui.generate_button,Qt.MouseButton.LeftButton)
            self.wait(lambda:not self.ui.busy)
            tiny.assert_called_once();qwen.assert_not_called()
            self.assertEqual(0,tiny.call_args.args[1])
        self.assertTrue(self.ui.play_button.isEnabled())
        self.assertEqual(1,self.ui.history_list.rowCount())


if __name__ == '__main__':
    unittest.main()
