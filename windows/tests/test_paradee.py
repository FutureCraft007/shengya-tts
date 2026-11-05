import hashlib
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from studio import light,paradee
from studio.core import Store,StudioError

class ParadeeTests(unittest.TestCase):
    def test_tiny_english_catalogue(self):
        spec=light.manifest('paradee')
        self.assertEqual(8461438,spec['bytes'])
        self.assertEqual('en',spec['voices'][0]['language'])
        self.assertEqual(1,len(spec['voices']))

    def test_adapter_preserves_nodes_and_adds_required_input(self):
        input_ids=paradee.field(1,b'input_ids');speed=paradee.field(1,b'speed')
        weight=paradee.field(5,b'unchanged-weights')
        graph=weight+paradee.field(11,input_ids)+paradee.field(11,speed)
        adapted=paradee.adapt_model(paradee.field(7,graph))
        graph=next(p for n,p,_ in paradee.fields(adapted) if n==7)
        self.assertIn(weight,graph)
        names=[next(p for n,p,_ in paradee.fields(i) if n==1) for n,i,_ in paradee.fields(graph) if n==11]
        self.assertEqual([b'input_ids',b'style',b'speed'],names)

    def test_english_validation_never_silently_drops_unknown_words(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'lexicon.txt';path.write_text('hello h e l o\nzero z\none w\ntwo t\n',encoding='utf-8')
            self.assertEqual('hello one two',paradee.prepare_text('hello 12',path))
            for text in ('你好','unlistedword','...'):
                with self.assertRaises(StudioError):paradee.prepare_text(text,path)

    def test_verified_official_model_import(self):
        fixture=os.environ.get('SHENGYA_PARADEE_TEST_MODEL')
        if not fixture:self.skipTest('Optional official model fixture')
        with tempfile.TemporaryDirectory() as folder:
            store=Store(Path(folder));light.install_archive(store,Path(fixture),threading.Event(),lambda _:None,'paradee')
            self.assertTrue(light.ready(store,'paradee'))
            self.assertFalse(light.ready(store,'kokoro'))
            path=light.model_path(store,'paradee')/'model.int8.onnx'
            self.assertEqual(light.manifest('paradee')['adaptedSha256'],hashlib.sha256(path.read_bytes()).hexdigest())
