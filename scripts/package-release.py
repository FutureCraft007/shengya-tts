"""Package verified local Windows 0.8.0 and Android 0.8.0 artifacts. Never uploads."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.8.0'
ANDROID_VERSION = '0.8.0'
OUT = ROOT/'dist'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def load(name): return json.loads((ROOT/'build/verification'/name).read_text(encoding='utf-8'))

def main():
    startup=load('windows-startup-0.8.0.json')
    installer=load('windows-installer-0.8.0.json')
    tests=load('windows-tests-0.8.0.json')
    assert startup['version']==VERSION and startup['nativeHelpExitCode']==0 and startup['nativeHelpPresent']
    assert startup['tabs']==5 and startup['cloudEndpointConfigurable'] and startup['ui']=='Qt 6'
    assert tests['tests']>=24 and tests['failures']==tests['errors']==0
    assert all(installer[k] for k in ('unrelatedDirectoryRejected','installAndReinstall','installedStartupAndNativeCli','uninstall','unknownFilePreserved','separateUserDataPreserved'))
    setup=OUT/f'ShengyaTTS-{VERSION}-Setup-x64.exe'
    assert setup.read_bytes()[:2]==b'MZ'
    assert installer['setupSha256']==sha(setup)
    subprocess.run([sys.executable,str(ROOT/'scripts/package-source.py')],check=True)
    source=OUT/f'shengya-tts-{ANDROID_VERSION}-source.zip'
    with zipfile.ZipFile(source) as archive: assert archive.testzip() is None
    apk=OUT/f'shengya-tts-{ANDROID_VERSION}-android-arm64.apk'
    android=load('android.json')
    assert android['version']==ANDROID_VERSION
    shutil.copyfile(android['apk'],apk)
    assert sha(apk)==android['sha256']
    frozen=load('kokoro-frozen-inference-0.8.0.json')
    assert frozen['version']==VERSION and frozen['audioSeconds']>0
    paradee=load('paradee-frozen-inference-0.8.0.json')
    assert paradee['version']==VERSION and paradee['audioSeconds']>0
    paradee_model=OUT/'paradee_int8.onnx'
    paradee_spec=json.loads((ROOT/'android/src/main/assets/paradee-model.json').read_text(encoding='utf-8'))
    assert paradee_model.stat().st_size==paradee_spec['bytes'] and sha(paradee_model)==paradee_spec['sha256']
    files=[setup,source,apk,paradee_model]
    artifacts=[{'file':p.name,'bytes':p.stat().st_size,'sha256':sha(p)} for p in files]
    report={'version':ANDROID_VERSION,'windowsVersion':VERSION,'androidVersion':ANDROID_VERSION,'artifacts':artifacts,'windowsStartup':startup,
        'windowsInstaller':installer,'windowsTests':tests,'androidVerification':android,'nativeEngineRebuilt':False,
        'kokoroRealInferenceBaseline':{'version':'0.7.0','report':load('kokoro-real-inference.json')},
        'kokoroFrozenInference':frozen,
        'paradeeRealInference':load('paradee-real-inference.json'),'paradeeFrozenInference':paradee,
        'paradeeModel':paradee_spec,
        'kokoroAndroidCallbackFix':load('kokoro-callback-apk-before-fix.json'),
        'notVerified':['physical Android inference and performance','Qwen model inference in this revision','live paid cloud calls','clean second Windows computer','remote CI']}
    model=OUT/'kokoro-int8-multi-lang-v1_1.tar.bz2'
    if model.exists():report['optionalModelPackage']={'file':model.name,'bytes':model.stat().st_size,'sha256':sha(model)}
    for name in ('RELEASE-MANIFEST.json','SHA256SUMS.txt'):
        old=OUT/name
        backup=OUT/(name.replace('.json','-0.5.0.json') if name.endswith('.json') else 'SHA256SUMS-0.5.0.txt')
        if old.exists() and not backup.exists():shutil.copyfile(old,backup)
    serialized=json.dumps(report,ensure_ascii=False,indent=2)
    (OUT/f'RELEASE-MANIFEST-{ANDROID_VERSION}.json').write_text(serialized,encoding='utf-8')
    (OUT/'RELEASE-MANIFEST.json').write_text(serialized,encoding='utf-8')
    (OUT/'SHA256SUMS.txt').write_text(''.join(f"{a['sha256']}  {a['file']}\n" for a in artifacts),encoding='utf-8')
    print(json.dumps(artifacts,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
