"""Install/uninstall this candidate in an isolated directory and registry key."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import winreg

ROOT = Path(__file__).resolve().parents[1]
SETUP = ROOT/'dist/ShengyaTTS-0.8.0-Setup-x64.exe'
KEY = r'Software\Microsoft\Windows\CurrentVersion\Uninstall\ShengyaTTS.InstallTest'

def registered():
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, KEY): return True
    except FileNotFoundError: return False

if registered(): raise SystemExit('An existing isolated install test is registered; inspect it before running again.')
qa = Path(tempfile.mkdtemp(prefix='shengya-060-qa-')).resolve()
install = qa/'program'
userdata = qa/'user-data'/'ShengyaTTS'
userdata.mkdir(parents=True)
fixture = userdata/'model-and-history-keep.txt'
fixture.write_text('isolated test data; preserve during uninstall', encoding='utf-8')
startup = subprocess.STARTUPINFO()
startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
startup.wShowWindow = subprocess.SW_HIDE

def run(args, timeout=90, **kwargs):
    result = subprocess.run([str(a) for a in args], timeout=timeout,
        startupinfo=startup, creationflags=subprocess.CREATE_NO_WINDOW, **kwargs)
    if result.returncode: raise RuntimeError(f'Owned QA process exited with {result.returncode}')

report = {'version':'0.8.0', 'testProfile':True,
          'setupSha256':hashlib.sha256(SETUP.read_bytes()).hexdigest()}
foreign = qa/'unrelated'
foreign.mkdir()
(foreign/'keep.txt').write_text('unrelated files', encoding='utf-8')
rejected = subprocess.run([str(SETUP), '/S', '/TestInstall', '/D='+str(foreign)],
    timeout=30, startupinfo=startup, creationflags=subprocess.CREATE_NO_WINDOW)
assert rejected.returncode == 2 and not registered()
assert (foreign/'keep.txt').read_text(encoding='utf-8') == 'unrelated files'
assert not (foreign/'ShengyaTTS.exe').exists()
report['unrelatedDirectoryRejected'] = True
for iteration in range(2):
    run([SETUP, '/S', '/TestInstall', '/D='+str(install)])
    assert (install/'ShengyaTTS.exe').is_file() and registered()
    assert (install/'licenses/Qt-LGPL-3.0.txt').is_file()
    if iteration == 0:
        (install/'user-added-keep.txt').write_text('user file', encoding='utf-8')
    assert (install/'user-added-keep.txt').read_text(encoding='utf-8') == 'user file'
report['installAndReinstall'] = True

env = os.environ.copy()
for name in ('PYTHONPATH','PYTHONHOME','QT_PLUGIN_PATH','QML2_IMPORT_PATH','QT_QPA_PLATFORM'):
    env.pop(name, None)
env['LOCALAPPDATA'] = str(qa/'user-data')
env['PATH'] = str(Path(os.environ['SYSTEMROOT'])/'System32')+';'+os.environ['SYSTEMROOT']
startup_report = ROOT/'build/verification/windows-startup-0.8.0.json'
run([install/'ShengyaTTS.exe', '--diagnose', startup_report], env=env)
info = json.loads(startup_report.read_text(encoding='utf-8'))
assert info['version']=='0.8.0' and info['ui']=='Qt 6' and info['tabs']==5 and info['cloudEndpointConfigurable']
assert info['nativeHelpExitCode']==0 and info['nativeHelpPresent']
report['installedStartupAndNativeCli'] = True

run([install/'Uninstall.exe', '/S'])
deadline = time.monotonic()+25
while ((install/'ShengyaTTS.exe').exists() or registered()) and time.monotonic()<deadline:
    time.sleep(.1)
assert not (install/'ShengyaTTS.exe').exists() and not registered()
assert not (install/'_internal').exists()
assert (install/'user-added-keep.txt').read_text(encoding='utf-8')=='user file'
assert fixture.read_text(encoding='utf-8')=='isolated test data; preserve during uninstall'
report.update(uninstall=True, unknownFilePreserved=True, separateUserDataPreserved=True,
              desktopShortcut='default selected; isolated test skips real desktop writes',
              cleanComputerTested=False)
(ROOT/'build/verification/windows-installer-0.8.0.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
# Delete only the temporary QA directory this script itself created.
assert qa.parent == Path(tempfile.gettempdir()).resolve() and qa.name.startswith('shengya-060-qa-')
print('Verified install/reinstall, installed startup, native CLI, uninstall and data retention.')
print('Cleaning owned QA directory:', qa)
shutil.rmtree(qa)
