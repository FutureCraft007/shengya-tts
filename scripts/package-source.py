import hashlib
import json
from pathlib import Path
import zipfile
from public_source import ROOT, scan

if __name__ == "__main__":
    files, findings = scan()
    if findings:
        raise SystemExit("Source checks failed. Run check-public-source.py for locations.")
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    target = output / "shengya-tts-0.8.0-source.zip"
    manifest = {}
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            relative = file.relative_to(ROOT).as_posix()
            data = file.read_bytes()
            manifest[relative] = hashlib.sha256(data).hexdigest()
            archive.writestr("shengya-tts/" + relative, data)
        archive.writestr("shengya-tts/SOURCE-MANIFEST.json", json.dumps(manifest, indent=2))
    print(f"Source archive: {target.name} ({target.stat().st_size:,} bytes)")
    print("Review docs/RELEASE.md before public distribution.")
