from pathlib import Path
import io
import json
import sys
import tarfile
import tempfile
import threading
import hashlib
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio import light
from studio.core import Store,StudioError,Cancelled

class LightModelTests(unittest.TestCase):
    def test_shared_catalogue_matches_real_voice_ids(self):
        spec=light.manifest();voices=spec['voices']
        self.assertEqual(set(range(103)),{v['id'] for v in voices})
        self.assertEqual(100,sum(v['language']=='zh' for v in voices))
        self.assertEqual(3,sum(v['language']=='en' for v in voices))
        self.assertEqual(147052523,spec['bytes'])

    def fixture(self,folder,entries):
        path=folder/'test.tar.bz2'
        with tarfile.open(path,'w:bz2') as archive:
            for name,data in entries:
                info=tarfile.TarInfo(name);info.size=len(data);archive.addfile(info,io.BytesIO(data))
        spec={'directory':'fixture','title':'Fixture','bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        return path,spec

    def test_verified_install_is_atomic_and_ready(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);store=Store(folder/'profile')
            path,spec=self.fixture(folder,[('fixture/model.onnx',b'model'),('fixture/espeak-ng-data/phontab',b'phones')])
            with patch.object(light,'manifest',return_value=spec),patch.object(light,'REQUIRED',{'model.onnx':5}):
                light.install_archive(store,path,threading.Event(),lambda _:None)
                self.assertTrue(light.ready(store))
                before=(light.model_path(store)/'model.onnx').read_bytes()
                path.write_bytes(b'corrupt')
                with self.assertRaises(StudioError):light.install_archive(store,path,threading.Event(),lambda _:None)
                self.assertEqual(before,(light.model_path(store)/'model.onnx').read_bytes())

    def test_archive_traversal_is_rejected_without_outside_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);store=Store(folder/'profile')
            path,spec=self.fixture(folder,[('fixture/../../escape',b'bad')])
            with patch.object(light,'manifest',return_value=spec):
                with self.assertRaises(StudioError):light.install_archive(store,path,threading.Event(),lambda _:None)
            self.assertFalse((folder/'escape').exists())
            self.assertFalse(list((store.root/'models/kokoro').glob('install-*')))

    def test_cancelled_install_preserves_existing_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);store=Store(folder/'profile')
            path,spec=self.fixture(folder,[('fixture/model.onnx',b'model')]);cancel=threading.Event();cancel.set()
            with patch.object(light,'manifest',return_value=spec):
                with self.assertRaises(Cancelled):light.install_archive(store,path,cancel,lambda _:None)
            self.assertFalse(list((store.root/'models').glob('kokoro/install-*')))

    def test_synthesis_without_models_fails_before_loading_native_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            engine=light.KokoroEngine(Store(Path(tmp)))
            with self.assertRaises(StudioError):engine.synthesize('你好',3,1.,4,threading.Event(),lambda _:None)

if __name__=='__main__':unittest.main()
