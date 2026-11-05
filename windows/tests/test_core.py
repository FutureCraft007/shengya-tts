import hashlib
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import wave

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from studio import core


def fixture_wav():
    stream = io.BytesIO()
    with wave.open(stream, "wb") as audio:
        audio.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
        audio.writeframes(b"\0" * 4)
    return stream.getvalue()


class ProtocolTests(unittest.TestCase):
    def test_wav_is_required_and_bounded(self):
        data = fixture_wav()
        parse = lambda raw, **kwargs: core.read_cloud_wav(
            io.BytesIO(raw), threading.Event(), time.monotonic() + 10, **kwargs)
        self.assertEqual(data, parse(data))
        with self.assertRaises(core.StudioError):
            parse(b'{"error":"invalid"}')
        with self.assertRaises(core.StudioError):
            parse(data, limit=8)

    def test_rejects_non_https_and_urls_with_credentials_or_redirect_data(self):
        for endpoint in ("http://example.com/tts", "https://key@example.com/tts",
                         "https://example.com/tts?key=x", "https://example.com/tts#key"):
            with self.subTest(endpoint=endpoint), self.assertRaises(core.StudioError):
                core.cloud_url(endpoint)


class StorageAndAudioTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = core.Store(Path(self.temp.name))

    def test_settings_never_serialize_credentials_and_paths_cannot_escape_records(self):
        self.store.save_settings({"language": "en", "api_key": "dummy-not-a-real-secret", "token": "dummy"})
        self.assertEqual({"language": "en"}, self.store.settings())
        with self.assertRaises(core.StudioError):
            self.store.delete_record("voices", "../models")

    @unittest.skipUnless(sys.platform == "win32", "Windows DPAPI")
    def test_real_dpapi_round_trip_and_deletion(self):
        endpoint = "https://example.com/tts"
        self.store.save_secret(endpoint, "unit-test-placeholder")
        self.assertNotIn(b"unit-test-placeholder", self.store.credential_path(endpoint).read_bytes())
        self.assertEqual("unit-test-placeholder", self.store.secret(endpoint))
        self.assertEqual("", self.store.secret("https://other.example.com/tts"))
        self.store.clear_secret(endpoint)
        self.assertEqual("", self.store.secret(endpoint))

    def test_model_import_verifies_before_replacing_existing_model(self):
        source = self.store.root / "tiny.gguf"
        source.write_bytes(b"verified")
        spec = (source.name, 8, hashlib.sha256(b"verified").hexdigest())
        with patch.dict(core.MODELS, {"base": spec}):
            core.import_models(self.store, [source], threading.Event())
            source.write_bytes(b"tampered")
            with self.assertRaises(core.StudioError):
                core.import_models(self.store, [source], threading.Event())
            self.assertEqual(b"verified", (self.store.root / "models" / source.name).read_bytes())

    def test_stereo_reference_resampling_preserves_duration_and_rejects_truncation(self):
        source = self.store.root / "source.wav"
        with wave.open(str(source), "wb") as audio:
            audio.setnchannels(2)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(struct.pack("<hh", 1000, 3000) * 48000)
        pcm, seconds = core.reference_pcm(source)
        self.assertEqual(3, seconds)
        self.assertEqual(3 * 24000 * 2, len(pcm))
        self.assertEqual(2000, struct.unpack_from("<h", pcm)[0])
        source.write_bytes(source.read_bytes()[:-100])
        with self.assertRaises(core.StudioError):
            core.reference_pcm(source)

    def test_reference_duration_is_not_silently_trimmed(self):
        source = self.store.root / "long.wav"
        core.save_wav(source, b"\0\0" * (31 * 24000))
        with self.assertRaises(core.StudioError):
            core.reference_pcm(source)

    def test_splitting_preserves_words_and_emoji(self):
        text = ("你好，Hello 🙂! " * 40).strip()
        chunks = core.split_text(text)
        self.assertTrue(all(0 < len(chunk) <= 160 for chunk in chunks))
        self.assertEqual("".join(text.split()), "".join("".join(chunks).split()))

    def test_speakers_are_read_from_model_metadata(self):
        def string(value):
            data = value.encode()
            return struct.pack("<Q", len(data)) + data
        path = self.store.root / "metadata.gguf"
        path.write_bytes(b"GGUF" + struct.pack("<IQQ", 3, 0, 1) + string("qwen3-tts.codec.speaker_names") +
                         struct.pack("<IIQ", 9, 8, 2) + string("Vivian") + string("Serena"))
        self.assertEqual(["serena", "vivian"], core.model_speakers(path))


class CloudRequestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = core.Store(Path(self.temp.name))
        self.config = {"cloud_endpoint": "https://example.com:8443/custom/tts",
                       "cloud_model": "test-model", "cloud_voice": "fixture-voice"}
        self.store.secret = lambda endpoint: "fixture-only" if endpoint == self.config["cloud_endpoint"] else ""
        self.client = core.CloudSpeech(self.store)
        self.text = "This is a cloud fixture test."

    def test_success_is_cached_and_does_not_resubmit_or_store_secret(self):
        class Response(io.BytesIO):
            status = 200
        class Connection:
            calls = []
            def __init__(self, host, port, timeout): self.host = (host, port)
            def request(self, *args, **kwargs): self.calls.append((self.host, args, kwargs))
            def getresponse(self): return Response(fixture_wav())
            def close(self): pass
        with patch.object(core.http.client, "HTTPSConnection", Connection):
            path = self.client.sample(self.config, self.text, threading.Event())
            self.assertEqual(path, self.client.sample(self.config, self.text, threading.Event()))
        self.assertEqual(1, len(Connection.calls))
        self.assertEqual(("example.com", 8443), Connection.calls[0][0])
        self.assertEqual(("POST", "/custom/tts"), Connection.calls[0][1])
        self.assertEqual("Bearer fixture-only", Connection.calls[0][2]["headers"]["Authorization"])
        self.assertEqual({"model":"test-model", "voice":"fixture-voice", "input":self.text,
                          "response_format":"wav"}, json.loads(Connection.calls[0][2]["body"]))
        with patch.object(core.http.client, "HTTPSConnection") as other:
            with self.assertRaises(core.StudioError):
                self.client.sample({**self.config, "cloud_endpoint":"https://other.example.com/tts"},
                                   self.text, threading.Event())
            other.assert_not_called()
        for file in (self.store.root / "cloud").iterdir():
            self.assertNotIn(b"fixture-only", file.read_bytes())
        with wave.open(str(path)) as audio:
            self.assertEqual(24000, audio.getframerate())
            self.assertEqual(2, audio.getnframes())

    def test_network_failure_keeps_checkpoint_and_blocks_second_submission(self):
        class Connection:
            calls = 0
            def __init__(self, *a, **k): pass
            def request(self, *a, **k):
                Connection.calls += 1
                raise OSError("Do not display provider data")
            def close(self): pass
        with patch.object(core.http.client, "HTTPSConnection", Connection):
            for _ in range(2):
                with self.assertRaises(core.StudioError):
                    self.client.sample(self.config, self.text, threading.Event())
        self.assertEqual(1, Connection.calls)
        self.assertEqual(1, len(list((self.store.root / "cloud").glob("*.pending"))))

    def test_redirect_is_rejected_and_releases_checkpoint(self):
        class Response(io.BytesIO):
            status = 307
        class Connection:
            headers = None
            def __init__(self, *a, **k): pass
            def request(self, *a, **k): Connection.headers = k["headers"]
            def getresponse(self): return Response()
            def close(self): pass
        with patch.object(core.http.client, "HTTPSConnection", Connection), self.assertRaises(core.StudioError):
            self.client.sample(self.config, self.text, threading.Event())
        self.assertEqual("Bearer fixture-only", Connection.headers["Authorization"])
        self.assertFalse(list((self.store.root / "cloud").glob("*.pending")))


class LocalWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = core.Store(Path(self.temp.name))
        self.engine = core.LocalEngine(self.store)
        self.calls = []
        def fake_run(arguments, cancel):
            self.calls.append(arguments)
            for flag in ("--extract-icl-prompt", "--extract-speaker-embedding"):
                if flag in arguments:
                    Path(arguments[arguments.index(flag) + 1]).write_text("{}")
                    return
            core.save_wav(Path(arguments[arguments.index("-o") + 1]), b"\0\0" * 24000)
        self.engine.run = fake_run
        self.engine.arguments = lambda mode, threads, cancel: ["--model-name", mode]

    def test_full_clone_is_reused_and_long_audio_is_merged(self):
        reference = self.store.root / "reference.wav"
        core.save_wav(reference, b"\0\0" * (3 * 24000))
        transcript = "An exact reference transcript."
        voice = self.engine.clone(reference, "Example", transcript, 2, threading.Event())
        call = self.calls[0]
        self.assertIn("--extract-icl-prompt", call)
        self.assertEqual(transcript, Path(call[call.index("--reference-text-file") + 1]).read_text())
        text = "这是需要保留的中文文案。" * 40
        result = self.engine.synthesize(text, "zh", "base", voice, "", 2, threading.Event(), lambda _: None)
        self.assertTrue(all("--icl-prompt" in call for call in self.calls[1:]))
        with wave.open(str(self.store.record_path("history", result["id"]) / "audio.wav")) as audio:
            self.assertEqual((len(self.calls) - 1) * 24000, audio.getnframes())

    def test_cancelled_clone_cleans_partial_files(self):
        reference = self.store.root / "reference.wav"
        core.save_wav(reference, b"\0\0" * (3 * 24000))
        self.engine.run = lambda *args: (_ for _ in ()).throw(core.Cancelled())
        with self.assertRaises(core.Cancelled):
            self.engine.clone(reference, "Example", "", 2, threading.Event())
        self.assertEqual([], list((self.store.root / "voices").iterdir()))


if __name__ == "__main__":
    unittest.main()
