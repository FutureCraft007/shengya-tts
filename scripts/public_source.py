"""A deterministic source selection shared by checks and archive packaging."""
from pathlib import Path
import hashlib
import os
import re

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED_DIRS = {".git", ".gradle", ".kotlin", ".cxx", ".build-tmp", "build", "dist",
                 "__pycache__", ".pytest_cache", ".idea", ".vscode", "node_modules", ".venv", "venv", "models"}
EXCLUDED_SOURCE_DIRS = {"external/qwen3-tts.cpp/ggml/examples/yolo"}
EXCLUDED_SUFFIXES = {".log", ".pdb", ".apk", ".aab", ".exe", ".dll", ".zip", ".rar", ".7z",
                     ".gguf", ".onnx", ".safetensors",
                     ".wav", ".mp3", ".m4a", ".flac", ".pyc", ".download", ".import"}
TOP_LEVEL_FILES = {"LICENSE", "README.md", "README.zh-CN.md", "THIRD_PARTY_NOTICES.md",
                   "CONTRIBUTING.md", "SECURITY.md", ".gitignore", "build.gradle.kts",
                   "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"}
TOP_LEVEL_DIRS = {"android", "windows", "external", "gradle", "scripts", "docs", ".github"}
SECRET_SUFFIXES = {".jks", ".keystore", ".pem", ".p12", ".pfx", ".key", ".dpapi", ".pending"}
SECRET_NAMES = {"credentials.json", "secrets.json", ".netrc", ".npmrc", ".pypirc", "id_rsa", "id_ed25519"}
REVIEWED_BINARIES = {
    "android/src/main/assets/rnnoise/rnnoise-70f1d256-int8.bin": "1ad07b428be34c74d9678d05b3c387b73ccf0e6203a1a5f9ce58689b30b303c8",
    # Gzip-compressed public Kokoro/Misaki English lexicon; see PARADEE.md.
    "android/src/main/assets/paradee-lexicon.bin": "2d816cfc5494d2ce170418a5921a952e08759d88bbc9f9ddfa9afcadb9df3f11",
}
PATTERNS = {
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "provider-token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9_-]{32,}|AKIA[A-Z0-9]{16})\b"),
    "credential-url": re.compile(r"https?://[^/\s\"']+:[^/@\s\"']+@"),
    "literal-credential": re.compile(
        r"(?i)\b(?:api[-_]?key|access[-_]?token|secret[-_]?key|password|client[-_]?secret)"
        r"[\"']?\s*[:=]\s*[\"'](?!test[-_]|dummy|fixture|example|placeholder)"
        r"[A-Za-z0-9_./+=-]{20,}[\"']"
    ),
    "private-network": re.compile(r"\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3})\b"),
    "personal-windows-path": re.compile(r"(?i)\b[A-Z]:[\\/]+Users[\\/]+(?!Public\b|Default\b|user(?:name)?\b|<|YOUR)[^\\/\s\"']+"),
    "personal-unix-path": re.compile(r"/(?:home|Users)/(?!user\b|<|YOUR)[^/\s\"']+"),
}


def source_files(root=ROOT):
    for folder, directories, names in os.walk(root):
        directory = Path(folder)
        directories[:] = sorted(d for d in directories if d not in EXCLUDED_DIRS and
                                not (directory / d).is_symlink() and
                                (directory / d).relative_to(root).as_posix() not in EXCLUDED_SOURCE_DIRS and
                                (directory != root or d in TOP_LEVEL_DIRS))
        for name in sorted(names):
            path = directory / name
            if path.is_symlink() or (directory == root and name not in TOP_LEVEL_FILES):
                continue
            if path.suffix.lower() in EXCLUDED_SUFFIXES or name == "local.properties" or name.startswith(("hs_err_", "replay_")):
                continue
            yield path


def scan(root=ROOT):
    findings = []
    files = list(source_files(root))
    for path in files:
        relative = path.relative_to(root).as_posix()
        name = path.name.lower()
        if (path.suffix.lower() in SECRET_SUFFIXES or name in SECRET_NAMES or
                (name.startswith(".env") and name != ".env.example")):
            findings.append((relative, 0, "credential-file"))
            continue
        data = path.read_bytes()
        if path.suffix == ".bin" and hashlib.sha256(data).hexdigest() != REVIEWED_BINARIES.get(relative):
            findings.append((relative, 0, "unreviewed-binary"))
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue  # Pinned Gradle wrapper, icons, and upstream images are not text credentials.
        for number, line in enumerate(text.splitlines(), 1):
            for rule, pattern in PATTERNS.items():
                # Public upstream examples are not this project's personal configuration.
                if relative.startswith("external/") and rule == "private-network":
                    continue
                if pattern.search(line):
                    findings.append((relative, number, rule))
    return files, findings
