"""Render this application's real widgets using an isolated, empty data directory."""
from pathlib import Path
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'windows'))
from studio.desktop import application, Studio
from studio.core import Store
root = Path(__file__).resolve().parents[1]
out = root/'build/verification/ui-0.8.0'
out.mkdir(parents=True, exist_ok=True)
qt = application()
with tempfile.TemporaryDirectory() as folder:
    store = Store(Path(folder)); store.save_settings({'language':'zh-CN'})
    window = Studio(store); window.resize(1320,840); window.show(); qt.processEvents()
    for index, name in enumerate(('studio','voices','cloud','history','settings')):
        window.navigate(index); qt.processEvents(); window.grab().save(str(out/(name+'.png')))
    window.navigate(0); window.mode.setCurrentIndex(window.mode.findData("kokoro")); qt.processEvents(); window.grab().save(str(out/"kokoro.png"))
    window.mode.setCurrentIndex(window.mode.findData("paradee")); qt.processEvents(); window.grab().save(str(out/"paradee.png"))
    window.resize(1000,700); qt.processEvents(); window.grab().save(str(out/'studio-compact.png'))
    window.resize(1920,1080); qt.processEvents(); window.grab().save(str(out/'studio-large.png'))
    window.close()
print(out)
