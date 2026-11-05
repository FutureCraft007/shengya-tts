"""Local storage, Qwen CLI, and the HTTPS speech API.

Only explicit cloud generation sends text and credentials over the network.
The application never retries an ambiguous paid request automatically.
"""
from __future__ import annotations

import io
import ctypes
from ctypes import wintypes
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from urllib.parse import urlsplit
import uuid
import wave

MODELS = {
    "tokenizer": ("qwen-tokenizer-12hz-Q4_K_M.gguf", 254_974_752, "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039"),
    "base": ("qwen-talker-0.6b-base-Q4_K_M.gguf", 628_905_056, "4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226"),
    "custom": ("qwen-talker-0.6b-customvoice-Q4_K_M.gguf", 604_878_080, "b3a7e6613d80f8a703c06267fc1e94d48ce91932ab82ab6e31c50f4ca4868e1e"),
}
LANGUAGES = ("auto", "zh", "en", "ja", "ko", "de", "fr", "es", "ru")


class StudioError(Exception):
    """An error key, with safe scalar details, translated at the UI boundary."""

    def __init__(self, key: str, detail: str = ""):
        super().__init__(key)
        self.key, self.detail = key, detail


class Cancelled(StudioError):
    def __init__(self):
        super().__init__("cancelled")


def asset_root() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys._MEIPASS)
    return Path(__file__).resolve().parents[2]


def atomic_bytes(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    try:
        with temporary.open("wb") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json(path: Path, value) -> None:
    atomic_bytes(path, json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8"))


def read_json(path: Path, default):
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        raise StudioError("invalid_storage") from error


def dpapi(value: bytes, decrypt: bool = False) -> bytes:
    """Encrypt for the current Windows user. Never fall back to plaintext."""
    if sys.platform != "win32":
        raise StudioError("windows_credentials")

    class Blob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]

    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    array = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source, output = Blob(len(value), array), Blob()
    operation = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                          ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise StudioError("credential_failure")
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        kernel.LocalFree(output.pbData)


class Store:
    def __init__(self, root: Path | None = None):
        self.root = root or Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "ShengyaTTS"
        for name in ("models", "voices", "history", "cloud", "work"):
            (self.root / name).mkdir(parents=True, exist_ok=True)

    def settings(self) -> dict:
        return read_json(self.root / "settings.json", {})

    def save_settings(self, settings: dict) -> None:
        allowed = {"language", "cloud_endpoint", "cloud_model", "cloud_voice", "speech_language", "threads", "mode", "kokoro_speed", "kokoro_voice"}
        values = {k: v for k, v in settings.items() if k in allowed}
        if values.get("cloud_endpoint"):
            try:
                cloud_url(values["cloud_endpoint"])
            except StudioError:
                values.pop("cloud_endpoint")
        write_json(self.root / "settings.json", values)

    def credential_path(self, endpoint: str) -> Path:
        identity = hashlib.sha256(endpoint.encode("utf-8")).hexdigest()
        return self.root / f"cloud-{identity}.dpapi"

    def save_secret(self, endpoint: str, secret: str) -> None:
        cloud_url(endpoint)
        secret = secret.strip().removeprefix("Bearer ").strip()
        validate_secret(secret)
        atomic_bytes(self.credential_path(endpoint), dpapi(secret.encode("utf-8")))

    def secret(self, endpoint: str) -> str:
        path = self.credential_path(endpoint)
        return dpapi(path.read_bytes(), True).decode("utf-8") if path.exists() else ""

    def clear_secret(self, endpoint: str):
        self.credential_path(endpoint).unlink(missing_ok=True)

    def records(self, group: str) -> list[dict]:
        assert group in ("voices", "history")
        return sorted((read_json(p, {}) for p in (self.root / group).glob("*/record.json")),
                      key=lambda x: x.get("created", 0), reverse=True)

    def record_path(self, group: str, record_id: str) -> Path:
        if group not in ("voices", "history") or not re.fullmatch(r"[0-9a-f]{32}", record_id):
            raise StudioError("invalid_storage")
        return self.root / group / record_id

    def delete_record(self, group: str, record_id: str):
        path = self.record_path(group, record_id)
        # Validated UUID and fixed parent prevent user text from becoming a delete path.
        if path.exists():
            shutil.rmtree(path)


def validate_secret(secret: str):
    if not secret or len(secret) > 4096 or any(not 33 <= ord(c) <= 126 for c in secret):
        raise StudioError("invalid_credentials")


def cloud_url(endpoint: str):
    try:
        url = urlsplit(endpoint)
        if (url.scheme != "https" or not url.hostname or url.username is not None
                or not url.path or url.path == "/" or url.port == 0
                or any(c in endpoint for c in "?#\\") or
                any(not 33 <= ord(c) <= 126 for c in endpoint)):
            raise ValueError
        return url
    except ValueError as error:
        raise StudioError("invalid_endpoint") from error


def read_cloud_wav(stream, cancelled: threading.Event, deadline: float,
                   limit: int = 16 * 1024 * 1024) -> bytes:
    data = bytearray()
    while True:
        if cancelled.is_set():
            raise Cancelled()
        if time.monotonic() >= deadline:
            raise StudioError("cloud_timeout")
        block = stream.read(min(64 * 1024, limit - len(data) + 1))
        if not block:
            break
        data.extend(block)
        if len(data) > limit:
            raise StudioError("cloud_size")
    try:
        with wave.open(io.BytesIO(data), "rb") as audio:
            expected = audio.getnframes() * audio.getsampwidth() * audio.getnchannels()
            if not 0 < expected <= len(data) or len(audio.readframes(audio.getnframes())) != expected:
                raise wave.Error("incomplete WAV")
    except (wave.Error, EOFError) as error:
        raise StudioError("cloud_protocol") from error
    return bytes(data)


def save_wav(path: Path, pcm: bytes, sample_rate: int = 24000):
    temp = path.with_suffix(".writing")
    try:
        with wave.open(str(temp), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(sample_rate)
            audio.writeframes(pcm)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


class CloudSpeech:
    def __init__(self, store: Store):
        self.store = store

    def sample(self, config: dict, text: str, cancel: threading.Event) -> Path:
        text = text.strip()
        if not 1 <= len(text) <= 2000:
            raise StudioError("sample_length")
        endpoint = config.get("cloud_endpoint", "")
        url = cloud_url(endpoint)
        model = config.get("cloud_model", "").strip()
        voice = config.get("cloud_voice", "").strip()
        if not model or not voice:
            raise StudioError("cloud_model_voice")
        key = self.store.secret(endpoint)
        validate_secret(key)
        body = json.dumps({"model": model, "voice": voice, "input": text,
                           "response_format": "wav"}).encode("utf-8")
        identity = hashlib.sha256(json.dumps([endpoint, hashlib.sha256(key.encode()).hexdigest(),
                                             body.decode("utf-8")]).encode()).hexdigest()
        path = self.store.root / "cloud" / f"{identity}.wav"
        checkpoint = path.with_suffix(".pending")
        if path.is_file():
            return path
        if checkpoint.exists():
            raise StudioError("ambiguous_request")
        if cancel.is_set():
            raise Cancelled()
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {key}"}
        connection = http.client.HTTPSConnection(url.hostname, port=url.port, timeout=30)
        atomic_bytes(checkpoint, b"submitted")
        try:
            connection.request("POST", url.path, body=body, headers=headers)
            with connection.getresponse() as response:
                if 300 <= response.status < 400:
                    checkpoint.unlink(missing_ok=True)
                    raise StudioError("cloud_redirect")
                if 400 <= response.status < 500:
                    checkpoint.unlink(missing_ok=True)
                    raise StudioError("cloud_rejected", str(response.status))
                if not 200 <= response.status < 300:
                    raise StudioError("cloud_http", str(response.status))
                audio = read_cloud_wav(response, cancel, time.monotonic() + 180)
            atomic_bytes(path, audio)
            checkpoint.unlink(missing_ok=True)
            return path
        except (OSError, http.client.HTTPException) as error:
            raise StudioError("cloud_network") from error
        finally:
            connection.close()


def verify_model(path: Path, spec: tuple, cancel: threading.Event | None = None):
    if not path.is_file() or path.stat().st_size != spec[1]:
        raise StudioError("model_size", spec[0])
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            if cancel and cancel.is_set():
                raise Cancelled()
            digest.update(block)
    if digest.hexdigest() != spec[2]:
        raise StudioError("model_hash", spec[0])


def import_models(store: Store, paths: list[Path], cancel: threading.Event):
    for path in paths:
        spec = next((item for item in MODELS.values() if item[0] == path.name), None)
        if spec is None:
            raise StudioError("model_name")
        verify_model(path, spec, cancel)
        target = store.root / "models" / path.name
        if path.resolve() != target.resolve():
            temporary = target.with_suffix(".import")
            try:
                with path.open("rb") as source, temporary.open("wb") as output:
                    while block := source.read(1024 * 1024):
                        if cancel.is_set():
                            raise Cancelled()
                        output.write(block)
                verify_model(temporary, spec, cancel)
                os.replace(temporary, target)
            finally:
                temporary.unlink(missing_ok=True)


def download_models(store: Store, mode: str, cancel: threading.Event, progress):
    if mode not in ("base", "custom"):
        raise StudioError("model_name")
    for key in ("tokenizer", mode):
        spec = MODELS[key]
        target = store.root / "models" / spec[0]
        if target.exists():
            verify_model(target, spec, cancel)
            continue
        partial = target.with_suffix(".download")
        offset = partial.stat().st_size if partial.exists() else 0
        if offset > spec[1]:
            partial.unlink()
            offset = 0
        url = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/main/" + spec[0] + "?download=true"
        if offset < spec[1]:
            request = urllib.request.Request(url, headers={"Range": f"bytes={offset}-"} if offset else {})
            with urllib.request.urlopen(request, timeout=30) as response:
                append = response.status == 206 and offset > 0
                if response.status not in (200, 206):
                    raise StudioError("download_failed")
                if response.status == 206:
                    expected = offset if append else 0
                    match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
                    if not match or int(match[1]) != expected or int(match[3]) != spec[1]:
                        raise StudioError("download_failed")
                count = offset if append else 0
                with partial.open("ab" if append else "wb") as output:
                    while block := response.read(256 * 1024):
                        if cancel.is_set():
                            raise Cancelled()
                        count += len(block)
                        if count > spec[1]:
                            raise StudioError("model_size", spec[0])
                        output.write(block)
                        progress(f"{spec[0]} · {count * 100 // spec[1]}%")
        try:
            verify_model(partial, spec, cancel)
        except StudioError as error:
            if error.key == "model_hash":
                partial.unlink(missing_ok=True)
            raise
        os.replace(partial, target)


def reference_pcm(path: Path) -> tuple[bytes, float]:
    """Bounded PCM16 WAV import with stereo downmix and duration-preserving resampling."""
    try:
        with wave.open(str(path), "rb") as audio:
            channels, width, rate, frames = audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes()
            if channels not in (1, 2) or width != 2 or not 8000 <= rate <= 192000:
                raise StudioError("reference_format")
            duration = frames / rate
            if not 3 <= duration <= 30:
                raise StudioError("reference_length")
            raw = audio.readframes(frames)
            if len(raw) != frames * channels * 2:
                raise StudioError("reference_format")
    except (wave.Error, EOFError) as error:
        raise StudioError("reference_format") from error
    values = [sample[0] for sample in struct.iter_unpack("<h", raw)]
    if channels == 2:
        values = [round((a + b) / 2) for a, b in zip(values[::2], values[1::2])]
    count = round(len(values) * 24000 / rate)
    pcm = bytearray(count * 2)
    for i in range(count):
        position = i * rate / 24000
        left = min(int(position), len(values) - 1)
        right = min(left + 1, len(values) - 1)
        value = round(values[left] + (values[right] - values[left]) * (position - left))
        struct.pack_into("<h", pcm, i * 2, max(-32768, min(32767, value)))
    return bytes(pcm), duration


def split_text(text: str, maximum: int = 160) -> list[str]:
    remaining, result = text.strip(), []
    while len(remaining) > maximum:
        candidates = [i + 1 for i, c in enumerate(remaining[:maximum]) if c in "。！？!?;；\n，, "]
        boundary = candidates[-1] if candidates and candidates[-1] >= maximum // 2 else maximum
        result.append(remaining[:boundary].strip())
        remaining = remaining[boundary:].lstrip()
    if remaining:
        result.append(remaining)
    return result


def model_speakers(path: Path) -> list[str]:
    """Read speaker names from the actual GGUF metadata, without loading tensors."""
    with path.open("rb") as stream:
        def number(fmt):
            size = struct.calcsize(fmt)
            data = stream.read(size)
            if len(data) != size:
                raise StudioError("model_name")
            return struct.unpack(fmt, data)[0]

        def string():
            size = number("<Q")
            if size > 16 * 1024 * 1024:
                raise StudioError("model_name")
            data = stream.read(size)
            if len(data) != size:
                raise StudioError("model_name")
            return data.decode("utf-8")

        formats = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}

        def value(kind, depth=0):
            if depth > 2:
                raise StudioError("model_name")
            if kind == 8:
                return string()
            if kind == 9:
                element, size = number("<I"), number("<Q")
                if size > 1_000_000:
                    raise StudioError("model_name")
                return [value(element, depth + 1) for _ in range(size)]
            if kind not in formats:
                raise StudioError("model_name")
            return number(formats[kind])

        if stream.read(4) != b"GGUF" or number("<I") not in (2, 3):
            raise StudioError("model_name")
        number("<Q")
        count = number("<Q")
        if count > 100_000:
            raise StudioError("model_name")
        names = []
        for _ in range(count):
            key, item = string(), value(number("<I"))
            if re.fullmatch(r"qwen3-tts\.speaker\.\d+\.name", key) and isinstance(item, str):
                names.append(item)
            if key == "qwen3-tts.codec.speaker_names" and isinstance(item, list):
                names.extend(item)
        return sorted({name.strip().lower() for name in names if isinstance(name, str) and name.strip()})


class LocalEngine:
    def __init__(self, store: Store, executable: Path | None = None):
        self.store = store
        self.executable = executable or (asset_root() / "native" / "qwen3-tts-cli.exe")
        if not self.executable.exists() and not getattr(sys, "frozen", False):
            build = asset_root() / "build/native"
            if not build.exists():
                build = asset_root() / "external/qwen3-tts.cpp/build"
            self.executable = next(build.rglob("qwen3-tts-cli.exe"), self.executable)

    def run(self, arguments: list[str], cancel: threading.Event):
        if cancel.is_set():
            raise Cancelled()
        if not self.executable.is_file():
            raise StudioError("engine_missing")
        flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        # stderr may contain user text in upstream logging. Discard it instead of persisting it.
        process = subprocess.Popen([str(self.executable), *arguments], cwd=self.store.root / "work",
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
        deadline = time.monotonic() + 1800
        try:
            while process.poll() is None:
                if cancel.wait(0.15):
                    raise Cancelled()
                if time.monotonic() > deadline:
                    raise StudioError("engine_timeout")
            if process.returncode:
                raise StudioError("engine_failed", str(process.returncode))
        finally:
            if process.poll() is None:
                process.terminate()
            process.wait()

    def arguments(self, mode: str, threads: int, cancel: threading.Event) -> list[str]:
        if mode not in ("base", "custom") or not 1 <= threads <= 64:
            raise StudioError("model_name")
        # Recheck digests before inference; downloads and imports also verify them.
        for key in ("tokenizer", mode):
            verify_model(self.store.root / "models" / MODELS[key][0], MODELS[key], cancel)
        return ["-m", str(self.store.root / "models"), "--model-name", MODELS[mode][0], "-j", str(threads)]

    def clone(self, source: Path, name: str, transcript: str, threads: int, cancel: threading.Event) -> dict:
        if not name.strip() or len(name) > 100 or len(transcript) > 2000:
            raise StudioError("voice_name")
        args = self.arguments("base", threads, cancel)
        pcm, duration = reference_pcm(source)
        identity = uuid.uuid4().hex
        target = self.store.record_path("voices", identity)
        target.mkdir()
        try:
            reference = target / "reference.wav"
            save_wav(reference, pcm)
            prompt_name = "prompt.json" if transcript.strip() else "embedding.json"
            args += ["-r", str(reference)]
            if transcript.strip():
                text_file = target / "transcript.txt"
                atomic_bytes(text_file, transcript.strip().encode("utf-8"))
                args += ["--reference-text-file", str(text_file), "--extract-icl-prompt", str(target / prompt_name)]
            else:
                args += ["--extract-speaker-embedding", str(target / prompt_name)]
            self.run(args, cancel)
            if not (target / prompt_name).is_file():
                raise StudioError("engine_failed")
            record = {"id": identity, "name": name.strip(), "transcript": transcript.strip(),
                      "prompt": prompt_name, "duration": duration, "created": time.time()}
            write_json(target / "record.json", record)
            return record
        except BaseException:
            self.store.delete_record("voices", identity)
            raise

    def synthesize(self, text: str, language: str, mode: str, voice: dict | None, speaker: str,
                   threads: int, cancel: threading.Event, progress) -> dict:
        if not text.strip() or len(text) > 2000 or language not in LANGUAGES:
            raise StudioError("text_length")
        args = self.arguments(mode, threads, cancel)
        args += ["-l", language, "--max-tokens", "2048"]
        if mode == "custom":
            if speaker not in model_speakers(self.store.root / "models" / MODELS["custom"][0]):
                raise StudioError("select_voice")
            args += ["--speaker", speaker]
        elif voice:
            folder = self.store.record_path("voices", voice["id"])
            prompt = voice.get("prompt")
            if prompt not in ("prompt.json", "embedding.json"):
                raise StudioError("invalid_storage")
            args += ["--icl-prompt" if prompt == "prompt.json" else "--speaker-embedding", str(folder / prompt)]
        identity = uuid.uuid4().hex
        target = self.store.record_path("history", identity)
        target.mkdir()
        try:
            chunks = split_text(text)
            with tempfile.TemporaryDirectory(dir=self.store.root / "work") as temp:
                with wave.open(str(target / "audio.wav"), "wb") as merged:
                    merged.setnchannels(1)
                    merged.setsampwidth(2)
                    merged.setframerate(24000)
                    for i, chunk in enumerate(chunks):
                        progress(f"{i + 1}/{len(chunks)}")
                        output = Path(temp) / f"{i}.wav"
                        self.run(args + ["-t", chunk, "-o", str(output)], cancel)
                        with wave.open(str(output), "rb") as audio:
                            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) != (1, 2, 24000) or not audio.getnframes():
                                raise StudioError("engine_failed")
                            merged.writeframes(audio.readframes(audio.getnframes()))
            record = {"id": identity, "text": text.strip(), "voice": speaker if mode == "custom" else (voice["name"] if voice else "Base"),
                      "language": language, "created": time.time()}
            write_json(target / "record.json", record)
            return record
        except BaseException:
            self.store.delete_record("history", identity)
            raise
