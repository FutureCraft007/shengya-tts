"""Retain dynamic Qt/runtime licenses and native child-process dependencies."""
from pathlib import Path
import importlib.metadata
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT/'dist/windows/ShengyaTTS-0.8.0'
LICENSES = BUNDLE/'licenses'
LICENSES.mkdir(parents=True, exist_ok=True)
shutil.copyfile(Path(sys.base_prefix)/'LICENSE.txt', LICENSES/'Python-LICENSE.txt')
for package in ('pyinstaller', 'PySide6_Essentials', 'shiboken6','sherpa-onnx','sherpa-onnx-core','numpy'):
    dist = importlib.metadata.distribution(package)
    for file in dist.files:
        if '/licenses/' in str(file):
            dest = LICENSES/package/file.name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(dist.locate_file(file), dest)
for source in (ROOT/'docs/licenses').glob('*.txt'):
    shutil.copyfile(source, LICENSES/source.name)
shutil.copytree(ROOT/'docs/licenses/qt-third-party', LICENSES/'qt-third-party', dirs_exist_ok=True)
shutil.copyfile(ROOT/'docs/QT-RUNTIME.md', LICENSES/'QT-RUNTIME.md')
shutil.copyfile(ROOT/'docs/KOKORO.md', LICENSES/'KOKORO.md')
shutil.copyfile(ROOT/'docs/PARADEE.md', LICENSES/'PARADEE.md')
internal = BUNDLE/'_internal'
for name in ('MSVCP140.dll', 'VCOMP140.DLL', 'VCRUNTIME140.dll', 'VCRUNTIME140_1.dll'):
    source = next((p/name for p in (internal, internal/'PySide6', internal/'shiboken6') if (p/name).is_file()), internal/name)
    if not source.is_file():
        raise SystemExit(f'Missing native runtime: {name}')
    shutil.copyfile(source, internal/'native'/name)
for name in ('Qt-LGPL-3.0.txt','Qt-GPL-3.0.txt','Qt-GPL-exception.txt'):
    assert (LICENSES/name).stat().st_size > 500
assert not list(internal.rglob('*Qt6WebEngine*'))
assert not list(internal.rglob('*Qt6VirtualKeyboard*'))
assert not list(internal.rglob('*Qt6Pdf*'))
assert not list(internal.glob('icu*.dll')), 'Do not shadow the Windows native ICU with another toolchain runtime.'
print('Dynamic Qt runtime, native dependencies and licenses retained.')
