import json
from pathlib import Path
import subprocess
import sys
import tempfile

from studio.app import main

if __name__ == "__main__":
    if len(sys.argv)==4 and sys.argv[1] in ('--diagnose-kokoro','--diagnose-paradee'):
        import threading
        import time
        import wave
        from studio import __version__
        from studio.app import application
        from studio.core import Store
        from studio.light import KokoroEngine
        qt=application()
        store=Store(Path(sys.argv[2]))
        started=time.monotonic()
        paradee=sys.argv[1]=='--diagnose-paradee'
        text='Hello, welcome to the voice studio. This is a small English voice running on your device.' if paradee else '你好，这是声芽配音中文轻量模型的真实测试。'
        record=KokoroEngine(store,'paradee' if paradee else 'kokoro').synthesize(text,0 if paradee else 3,1.,4,threading.Event(),lambda _:None)
        output=store.record_path('history',record['id'])/'audio.wav'
        with wave.open(str(output),'rb') as audio:seconds=audio.getnframes()/audio.getframerate()
        report={'version':__version__,'model':'Paradee-8M-v1.1 INT8' if paradee else 'Kokoro-82M INT8','wallSeconds':round(time.monotonic()-started,3),'audioSeconds':round(seconds,3),'path':str(output)}
        Path(sys.argv[3]).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    elif len(sys.argv) == 3 and sys.argv[1] == "--diagnose":
        from studio import __version__
        from studio.app import Studio, application
        from studio.core import Store, asset_root
        with tempfile.TemporaryDirectory() as directory:
            qt = application()
            app = Studio(Store(Path(directory)))
            try:
                qt.processEvents()
                native = subprocess.run([str(asset_root() / "native/qwen3-tts-cli.exe"), "--help"],
                    capture_output=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
                report = {"application": "Shengya TTS", "version": __version__, "title": app.windowTitle(),
                    "tabs": app.pages.count(), "cloudEndpointConfigurable": app.cloud_endpoint.isEnabled(), "ui": "Qt 6",
                    "nativeHelpExitCode": native.returncode,
                    "nativeHelpPresent": b"usage" in (native.stdout + native.stderr).lower()}
                Path(sys.argv[2]).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
                if native.returncode or not report["nativeHelpPresent"] or not report["cloudEndpointConfigurable"]:
                    raise SystemExit(1)
            finally:
                app.close()
    else:
        main()
