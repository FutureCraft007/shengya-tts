"""Paradee adapter: preserve every weight, add an unused style input for sherpa.

Only the graph input declaration and metadata change. The phoneme IDs, speed
and waveform graph are the author's original INT8 model.
"""
from __future__ import annotations
import gzip
import re
from pathlib import Path
from .core import StudioError, asset_root

def varint(value):
    result=bytearray()
    while value>127:result.append((value&127)|128);value>>=7
    result.append(value);return bytes(result)

def field(number,value):
    return varint(number<<3|2)+varint(len(value))+value

def fields(data):
    def read(offset):
        value=0;shift=0
        while True:
            if offset>=len(data) or shift>63:raise ValueError('Invalid protobuf')
            b=data[offset];offset+=1;value|=(b&127)<<shift
            if b<128:return value,offset
            shift+=7
    offset=0
    while offset<len(data):
        begin=offset;tag,offset=read(offset);wire=tag&7
        if wire==2:
            size,offset=read(offset);payload=data[offset:offset+size];offset+=size
        elif wire==0:_,offset=read(offset);payload=b''
        elif wire in (1,5):offset+=8 if wire==1 else 4;payload=b''
        else:raise ValueError('Unsupported protobuf wire type')
        if offset>len(data):raise ValueError('Truncated protobuf')
        yield tag>>3,payload,data[begin:offset]

def adapt_model(data):
    # ValueInfoProto: float style[1,1]. It is intentionally unused by Paradee.
    dim=field(1,b'\x08\x01')
    tensor=b'\x08\x01'+field(2,dim+dim)
    style=field(1,b'style')+field(2,field(1,tensor))
    result=bytearray();found=False
    for number,payload,raw in fields(data):
        if number!=7:result.extend(raw);continue
        graph=bytearray();inputs=[]
        for n,p,r in fields(payload):
            if n==11:inputs.append(p)
            else:graph.extend(r)
        names=[next(p for n,p,_ in fields(i) if n==1).decode() for i in inputs]
        if names!=['input_ids','speed']:raise ValueError('Unsupported Paradee model inputs')
        graph.extend(field(11,inputs[0])+field(11,style)+field(11,inputs[1]))
        result.extend(field(7,graph));found=True
    if not found:raise ValueError('Missing ONNX graph')
    for key,value in {'model_type':'kokoro','sample_rate':'24000','version':'2','voice':'en-us',
        'n_speakers':'1','style_dim':'512,1,1','speaker_names':'af_heart',
        'source_model':'Paradee-8M-v1.1','shengya_adapter':'1'}.items():
        result.extend(field(14,field(1,key.encode())+field(2,value.encode())))
    return bytes(result)

def install_files(source,folder):
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'model.int8.onnx').write_bytes(adapt_model(Path(source).read_bytes()))
    assets=asset_root()/'android/src/main/assets'
    (folder/'tokens.txt').write_bytes((assets/'paradee-tokens.txt').read_bytes())
    with gzip.open(assets/'paradee-lexicon.bin','rb') as stream:(folder/'lexicon.txt').write_bytes(stream.read())
    (folder/'voices.bin').write_bytes(bytes(512*4))

def prepare_text(text,lexicon):
    if any(ord(c)>127 and c not in '’‘“”–—…' for c in text):
        raise StudioError('unexpected','Paradee 只支持英文，请切换 Kokoro 或千问朗读中文。')
    text=text.translate(str.maketrans({'’':"'",'‘':"'",'“':'"','”':'"','–':'-','—':'-','…':'...'}))
    # Keep numeric handling explicit and identical on both clients.
    digits=('zero','one','two','three','four','five','six','seven','eight','nine')
    text=re.sub(r'\d+',lambda m:' '.join(digits[int(c)] for c in m[0]),text)
    known={line.split()[0].lower() for line in Path(lexicon).read_text(encoding='utf-8').splitlines() if line.strip()}
    words=re.findall(r"[A-Za-z]+(?:'[A-Za-z]+)*",text)
    missing=list(dict.fromkeys(w for w in words if w.lower() not in known))
    if not words:raise StudioError('text_length')
    if missing:raise StudioError('unexpected','英文词典暂不包含：'+', '.join(missing[:5])+'。请修改拼写或切换其他模型。')
    return text
