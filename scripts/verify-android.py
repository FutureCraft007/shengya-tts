"""Verify the actual release APK; does not install, call providers, or read credentials."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import subprocess
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'android/build/outputs/apk/release/android-release.apk'
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
build_tools = sdk / 'build-tools/36.0.0'
checks = []

def check(value, name):
    if not value:
        raise AssertionError(name)
    checks.append(name)


def dex_methods(data):
    """Read method descriptors from the APK, after D8/desugaring."""
    string_count,string_offset=struct.unpack_from('<II',data,56)
    strings=[]
    for i in range(string_count):
        offset=struct.unpack_from('<I',data,string_offset+4*i)[0]
        while data[offset]&128:offset+=1
        offset+=1
        strings.append(data[offset:data.index(b'\0',offset)].decode('utf-8',errors='replace'))
    type_count,type_offset=struct.unpack_from('<II',data,64)
    types=[strings[struct.unpack_from('<I',data,type_offset+4*i)[0]] for i in range(type_count)]
    proto_count,proto_offset=struct.unpack_from('<II',data,72)
    protos=[]
    for i in range(proto_count):
        _,result,offset=struct.unpack_from('<III',data,proto_offset+12*i)
        count=struct.unpack_from('<I',data,offset)[0] if offset else 0
        arguments=''.join(types[struct.unpack_from('<H',data,offset+4+2*j)[0]] for j in range(count))
        protos.append('('+arguments+')'+types[result])
    count,offset=struct.unpack_from('<II',data,88)
    result=set()
    for i in range(count):
        cls,proto,name=struct.unpack_from('<HHI',data,offset+8*i)
        result.add(types[cls]+'->'+strings[name]+protos[proto])
    return result

def run(args):
    result = subprocess.run([str(a) for a in args], capture_output=True, encoding='utf-8', errors='replace')
    check(result.returncode == 0, 'command: ' + str(args[0].name if isinstance(args[0], Path) else args[0]))
    return result.stdout

badging = run([build_tools / 'aapt.exe', 'dump', 'badging', apk])
check("package: name='com.qwen.tts.android.zh'" in badging, 'independent Chinese package ID')
check("versionName='0.8.0'" in badging, 'version 0.8.0')
check("versionCode='12'" in badging, 'version code 12 supports upgrade from code 11')
check("sdkVersion:'31'" in badging, 'Android 12 minimum')
check("native-code: 'arm64-v8a'" in badging, 'ARM64 ABI')
check('声芽配音' in badging, 'Chinese launcher label')
signature = run([build_tools / 'apksigner.bat', 'verify', '--verbose', '--print-certs', apk])
check('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'APK v2 signature')

with zipfile.ZipFile(apk) as archive:
    methods=set().union(*(dex_methods(archive.read(name)) for name in archive.namelist() if name.startswith('classes') and name.endswith('.dex')))
    check('Lcom/qwen/tts/android/kokoro/KokoroGenerationCallback;->invoke([F)Ljava/lang/Integer;' in methods,
          'packaged DEX contains the typed boxed Kokoro JNI callback')
    check(archive.testzip() is None, 'ZIP integrity')
    names = archive.namelist()
    check(not any(n.endswith('.gguf') for n in names), 'model weights downloaded separately')
    check(not any(n.endswith('.onnx') for n in names), 'Kokoro weights downloaded separately')
    light=json.loads(archive.read('assets/kokoro-model.json'))
    check(light['bytes']==147052523 and len(light['voices'])==103,'Kokoro catalogue and fixed archive size')
    check(sum(v['language']=='zh' for v in light['voices'])==100,'100 Chinese Kokoro voices')
    paradee=json.loads(archive.read('assets/paradee-model.json'))
    check(paradee['bytes']==8461438 and len(paradee['voices'])==1 and paradee['voices'][0]['language']=='en','Paradee fixed tiny English model catalogue')
    for asset in ('paradee-lexicon.bin','paradee-tokens.txt','paradee-model.json'):
        check(archive.read('assets/'+asset)==(root/'android/src/main/assets'/asset).read_bytes(),'Paradee packaged resource: '+asset)
    check(len(archive.read('assets/licenses/Paradee-Apache-2.0.txt'))>10000,'Paradee Apache license retained')
    check('lib/arm64-v8a/libsherpa-onnx-jni.so' in names,'Kokoro native runtime packaged')
    dex = b''.join(archive.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
    for text in ('文字配音', '本地音色库', '云端语音合成', '导入音频并创建音色', 'HTTPS 接口地址', '音色 ID', '从手机导入模型文件', '保存配置', '本机降噪', '降噪版', '取消降噪', '导出降噪版', '导出原声'):
        check(text.encode('utf-8') in dex, 'packaged UI: ' + text)
    for component in ('CloudRepository', 'CloudSecrets', 'CloudProtocol', 'AudioImporter', 'ModelStorage', 'QwenEngine', 'LocalSpeechDenoiser', 'SpeechPreservationGuard', 'LocalApiService', 'LoopbackServer', 'ApiJobs', 'EngineHost', 'RemoteWorkerPool', 'WorkerSynthesizer', 'QwenWorker6'):
        check(component.encode() in dex, 'packaged component: ' + component)
    native = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    for name in native:
        data = archive.read(name)
        check(name.startswith('lib/arm64-v8a/') and data[:4] == b'\x7fELF' and int.from_bytes(data[18:20], 'little') == 183, 'AArch64 ELF: ' + name)
    jni = archive.read('lib/arm64-v8a/libqwen3_tts_jni.so')
    for name in ('nativeLoadModels', 'nativeExtractSpeakerEmbedding', 'nativeGetAvailableSpeakers', 'nativeSynthesize', 'nativeExtractIclPrompt', 'nativeSynthesizeWithIclPrompt'):
        check(('Java_com_qwen_tts_studio_engine_QwenEngine_' + name).encode() in jni, 'JNI export: ' + name)
    rnnoise = archive.read('lib/arm64-v8a/libqwen_rnnoise.so')
    for name in ('create', 'process', 'destroy'):
        check(('Java_com_qwen_tts_android_audio_RnnoiseNative_' + name).encode() in rnnoise, 'RNNoise JNI export: ' + name)
    model = archive.read('assets/rnnoise/rnnoise-70f1d256-int8.bin')
    check(len(model) == 3544320 and hashlib.sha256(model).hexdigest() == '1ad07b428be34c74d9678d05b3c387b73ccf0e6203a1a5f9ce58689b30b303c8', 'bundled RNNoise model size and SHA-256')
    check(b'Xiph.Org Foundation' in archive.read('assets/rnnoise/COPYING'), 'RNNoise license bundled')
    check(b'70f1d256acd4b34a572f999a05c87bf00b67730d' in archive.read('assets/rnnoise/MODEL-METADATA.txt'), 'RNNoise provenance bundled')
    check('assets/volcengine-voices.json' not in archive.namelist(), 'third-party voice catalog is absent')

test_reports = list((root / 'android/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
check(len(test_reports) >= 2, 'test reports present')
tests = failures = errors = 0
for path in test_reports:
    suite = ET.parse(path).getroot()
    tests += int(suite.get('tests', 0))
    failures += int(suite.get('failures', 0))
    errors += int(suite.get('errors', 0))
check(tests >= 74 and failures == errors == 0, 'all HTTP, manual-start, JVM, migration and Compose UI regression tests pass')
callback_suite = ET.parse(root / 'android/build/test-results/testDebugUnitTest/TEST-com.qwen.tts.android.kokoro.KokoroCallbackTest.xml').getroot()
check(int(callback_suite.get('tests',0)) >= 2 and int(callback_suite.get('failures',0)) == int(callback_suite.get('errors',0)) == 0,
      'Kokoro JNI typed callback and cancellation regression tests pass')
paradee_suite=ET.parse(root/'android/build/test-results/testDebugUnitTest/TEST-com.qwen.tts.android.kokoro.ParadeeAdapterTest.xml').getroot()
check(int(paradee_suite.get('tests',0))>=3 and int(paradee_suite.get('failures',0))==int(paradee_suite.get('errors',0))==0,'Paradee adapter, English validation and model import tests pass')
navigation_suite = ET.parse(root / 'android/build/test-results/testDebugUnitTest/TEST-com.qwen.tts.android.ui.AppNavigationTest.xml').getroot()
check(int(navigation_suite.get('tests', 0)) >= 5 and int(navigation_suite.get('failures', 0)) == int(navigation_suite.get('errors', 0)) == 0, 'cloud navigation and legacy saved-stack regression tests pass')
for name, minimum in [('ApiConcurrencyTest', 6), ('RemoteWorkerPoolTest', 4), ('ApiConfigurationTest', 1), ('LocalApiPanelTest', 1)]:
    suite = ET.parse(root / f'android/build/test-results/testDebugUnitTest/TEST-com.qwen.tts.android.api.{name}.xml').getroot()
    check(int(suite.get('tests', 0)) >= minimum and int(suite.get('failures', 0)) == int(suite.get('errors', 0)) == 0, 'six-lane regression: ' + name)
manifest = run([build_tools / 'aapt.exe', 'dump', 'xmltree', apk, 'AndroidManifest.xml'])
for index in range(1, 7):
    check(f'com.qwen.tts.android.api.QwenWorker{index}' in manifest and f':qwen_worker_{index}' in manifest, f'packaged isolated worker process {index}')
lint = ET.parse(root / 'android/build/reports/lint-results-debug.xml').getroot()
lint_errors = [n for n in lint.findall('issue') if n.get('severity') in ('Error', 'Fatal')]
check(not lint_errors, 'Android Lint: no errors')

report = {
    'verifiedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
    'apk': str(apk.resolve()), 'bytes': apk.stat().st_size,
    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'package': 'com.qwen.tts.android.zh', 'version': '0.8.0',
    'checksPassed': len(checks), 'checks': checks,
    'unitTests': {'tests': tests, 'failures': failures, 'errors': errors},
    'lintErrors': len(lint_errors),
    'notVerified': ['physical Android installation and UI', 'real Android audio decoder formats', 'model inference and voice similarity', 'RNNoise native inference and listening quality on a physical phone', 'vivo X300 Pro performance', 'live paid cloud provider requests'],
}
out = apk.parent / 'verification.json'
out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: report[k] for k in ('bytes', 'sha256', 'checksPassed', 'unitTests', 'lintErrors')}, ensure_ascii=False, indent=2))
print('Report: ' + str(out))
