"""Build deterministic application artwork from our own vector icon."""
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'windows'))
from studio.desktop import application
from studio.design import icon
from PIL import Image
root = Path(__file__).resolve().parents[1]
app = application()
assets = root/'windows/assets'
assets.mkdir(exist_ok=True)
icon('brand', size=256).pixmap(256,256).save(str(assets/'shengya.png'))
with Image.open(assets/'shengya.png') as source:
    source.save(assets/'shengya.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print('Application icon created.')
