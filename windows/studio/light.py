"""Verified Kokoro Chinese/English model installation and offline CPU inference."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import threading
import time
import urllib.request
import uuid
import wave
from .core import Store, StudioError, Cancelled, asset_root, write_json, split_text

REQUIRED = {'model.int8.onnx':114203914, 'voices.bin':53790720,
            'tokens.txt':1111, 'lexicon-zh.txt':2120131,
            'lexicon-us-en.txt':5956885, 'date-zh.fst':59154,
            'number-zh.fst':64482, 'phone-zh.fst':88630}

def manifest(model="kokoro"):
    return json.loads((asset_root()/f'android/src/main/assets/{model}-model.json').read_text(encoding='utf-8'))

def model_path(store,model='kokoro'): return store.root/'models'/model/manifest(model)['directory']

def required(spec): return spec.get('required',REQUIRED)

def ready(store,model="kokoro"):
    spec=manifest(model);folder=model_path(store,model)
    try:
        marker=json.loads((folder/'installed.json').read_text(encoding='utf-8'))
        if spec.get('adaptedSha256') and marker.get('adaptedSha256')!=spec['adaptedSha256']:return False
        return marker.get('archiveSha256')==spec['sha256'] and all(
            (folder/name).is_file() and (folder/name).stat().st_size==size for name,size in required(spec).items())
    except (OSError, ValueError): return False

def check_cancel(cancel):
    if cancel.is_set(): raise Cancelled()

def install_archive(store, source, cancel, progress,model="kokoro"):
    spec=manifest(model); source=Path(source)
    if source.stat().st_size!=spec['bytes']: raise StudioError('unexpected','模型文件大小不正确，请下载当前模型的官方文件。')
    progress('校验模型包 SHA-256…'); digest=hashlib.sha256()
    with source.open('rb') as stream:
        for data in iter(lambda:stream.read(1024*1024), b''):
            check_cancel(cancel);digest.update(data)
    if digest.hexdigest()!=spec['sha256']: raise StudioError('unexpected','模型包 SHA-256 校验失败。')
    root=model_path(store,model).parent;root.mkdir(parents=True,exist_ok=True)
    stage=root/('install-'+uuid.uuid4().hex);stage.mkdir();backup=None
    target=model_path(store,model)
    try:
        progress('解压中文模型与音色…'); total=0; count=0
        if spec.get('kind')=='paradee-onnx':
            from .paradee import install_files
            install_files(source,stage/spec['directory'])
        else:
            with tarfile.open(source, 'r:bz2') as archive:
                for member in archive:
                    check_cancel(cancel);count+=1
                    relative=PurePosixPath(member.name)
                    if (not relative.parts or relative.parts[0]!=spec['directory'] or relative.is_absolute()
                        or '..' in relative.parts or '\\' in member.name or count>2048
                        or not (member.isdir() or member.isfile())):raise StudioError('unexpected','模型包中有非法路径或文件类型。')
                    output=stage.joinpath(*relative.parts)
                    if not output.resolve().is_relative_to(stage.resolve()): raise StudioError('invalid_storage')
                    # The current runtime uses lexicons directly. Do not install the
                    # old external phonemizer data or the unused segmentation data.
                    if len(relative.parts)>1 and relative.parts[1] in ('espeak-ng-data','dict'):continue
                    if member.isdir():output.mkdir(parents=True,exist_ok=True);continue
                    total+=member.size
                    if member.size<0 or total>450*1024*1024:raise StudioError('unexpected','模型解压大小超限。')
                    output.parent.mkdir(parents=True,exist_ok=True)
                    with archive.extractfile(member) as source_file, output.open('wb') as destination:
                        for block in iter(lambda:source_file.read(128*1024),b''):
                            check_cancel(cancel);destination.write(block)
        extracted=stage/spec['directory']
        if not all((extracted/name).is_file() and (extracted/name).stat().st_size==size for name,size in required(spec).items()):raise StudioError('unexpected','模型包缺少必要文件。')
        marker={'archiveSha256':spec['sha256']}
        if spec.get('adaptedSha256'):
            if hashlib.sha256((extracted/'model.int8.onnx').read_bytes()).hexdigest()!=spec['adaptedSha256']:raise StudioError('unexpected','模型适配校验失败。')
            marker['adaptedSha256']=spec['adaptedSha256']
        write_json(extracted/'installed.json',marker)
        check_cancel(cancel)
        if target.exists():backup=root/('previous-'+uuid.uuid4().hex);os.replace(target,backup)
        try:os.replace(extracted,target)
        except BaseException:
            if backup is not None:os.replace(backup,target);backup=None
            raise
        progress(spec['title']+'已就绪')
    finally:
        shutil.rmtree(stage,ignore_errors=True)
        if backup is not None:shutil.rmtree(backup,ignore_errors=True)

def download(store, cancel, progress,model="kokoro"):
    spec=manifest(model);root=model_path(store,model).parent;root.mkdir(parents=True,exist_ok=True)
    partial=root/(spec['archive']+'.download')
    if partial.exists() and partial.stat().st_size>spec['bytes']:partial.unlink()
    count=partial.stat().st_size if partial.exists() else 0
    if count<spec['bytes']:
        request=urllib.request.Request(spec['url'],headers={'Accept-Encoding':'identity',**({'Range':f'bytes={count}-'} if count else {})})
        progress(f'连接下载站点 · {count/spec["bytes"]:.0%}')
        with urllib.request.urlopen(request, timeout=30) as response:
            append=response.status==206 and count>0
            if response.status not in (200,206):raise StudioError('unexpected','下载站点返回了错误状态。')
            if response.status==206 and not response.headers.get('Content-Range','').startswith(f'bytes {count if append else 0}-'):raise StudioError('unexpected','下载续传位置不正确。')
            count=count if append else 0;last=0
            with partial.open('ab' if append else 'wb') as output:
                while True:
                    check_cancel(cancel);data=response.read(128*1024)
                    if not data:break
                    count+=len(data)
                    if count>spec['bytes']:raise StudioError('unexpected','下载文件大小超限。')
                    output.write(data)
                    if time.monotonic()-last>.25:progress(f'下载模型 · {count/1e6:.1f} / {spec["bytes"]/1e6:.1f} MB · {count/spec["bytes"]:.0%}');last=time.monotonic()
    if partial.stat().st_size!=spec['bytes']:raise StudioError('unexpected','下载未完成，再次点击可续传。')
    try:install_archive(store,partial,cancel,progress,model)
    except Cancelled:raise
    except Exception:
        # Retain only a verified complete package; a checksum error must be retryable.
        h=hashlib.sha256(partial.read_bytes()).hexdigest()
        if h!=spec['sha256']:partial.unlink(missing_ok=True)
        raise
    partial.unlink(missing_ok=True)

class KokoroEngine:
    def __init__(self,store,model="kokoro"):self.store=store;self.model=model
    def synthesize(self,text,voice_id,speed,threads,cancel,progress):
        spec=manifest(self.model);voices={v['id']:v for v in spec['voices']}
        if not 1<=len(text.strip())<=2000:raise StudioError('text_length')
        if voice_id not in voices or not .5<=speed<=2 or not 1<=threads<=64:raise StudioError('select_voice')
        if not ready(self.store,self.model):raise StudioError('unexpected','请先下载或导入当前模型。')
        import numpy as np
        import sherpa_onnx
        folder=model_path(self.store,self.model)
        source_text=text
        if self.model=='paradee':
            from .paradee import prepare_text
            text=prepare_text(text,folder/'lexicon.txt')
        config=sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(folder/'model.int8.onnx'),
                voices=str(folder/'voices.bin'),tokens=str(folder/'tokens.txt'),
                lexicon=','.join(str(folder/f) for f in spec.get('lexicons',('lexicon-zh.txt','lexicon-us-en.txt')))),
            num_threads=threads,provider='cpu',debug=False),
            rule_fsts=','.join(str(folder/f) for f in spec.get('ruleFsts',('phone-zh.fst','date-zh.fst','number-zh.fst'))),max_num_sentences=1)
        if not config.validate():raise StudioError('unexpected','Kokoro 模型配置不完整。')
        check_cancel(cancel);progress('加载轻量模型…');started=time.monotonic();tts=sherpa_onnx.OfflineTts(config)
        identity=uuid.uuid4().hex;target=self.store.record_path('history',identity);target.mkdir()
        try:
            chunks=split_text(text,80)
            with wave.open(str(target/'audio.wav'),'wb') as output:
                output.setparams((1,2,24000,0,'NONE','not compressed'))
                for index,chunk in enumerate(chunks):
                    check_cancel(cancel);progress(f'轻量配音 · {index+1}/{len(chunks)}')
                    audio=tts.generate(chunk,sid=voice_id,speed=speed,callback=lambda *_:0 if cancel.is_set() else 1)
                    check_cancel(cancel)
                    if audio.sample_rate!=24000 or len(audio.samples)==0:raise StudioError('engine_failed')
                    output.writeframes((np.clip(audio.samples,-1,1)*32767).astype('<i2').tobytes())
            record={'id':identity,'text':source_text,'voice':('Paradee · ' if self.model=='paradee' else 'Kokoro · ')+voices[voice_id]['name'],
                'language':voices[voice_id]['language'],'created':time.time(),'engine':self.model,
                'voiceId':voice_id,'speed':speed,'synthesisMillis':round((time.monotonic()-started)*1000)}
            write_json(target/'record.json',record);return record
        except BaseException:
            shutil.rmtree(target,ignore_errors=True);raise
        finally:del tts
