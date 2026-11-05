from pathlib import Path
import gzip,hashlib,json,sys
root=Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'windows'))
from studio.paradee import adapt_model
work=root/'build/paradee';assets=root/'android/src/main/assets'
config=json.loads((work/'config.json').read_text(encoding='utf-8'));vocab=config['vocab']
tokens='\n'.join(f'{c} {i}' for c,i in sorted(vocab.items(),key=lambda item:item[1]))+'\n'
(assets/'paradee-tokens.txt').write_text(tokens,encoding='utf-8',newline='\n')
original=(root/'build/kokoro/real-test-data/models/kokoro/kokoro-int8-multi-lang-v1_1/lexicon-us-en.txt').read_text(encoding='utf-8')
lines=[]
for line in original.splitlines():
    parts=line.split()
    if len(parts)>1 and all(c in vocab for p in parts[1:] for c in p):lines.append(line)
lexicon=('\n'.join(lines)+'\n').encode()
(assets/'paradee-lexicon.bin').write_bytes(gzip.compress(lexicon,mtime=0))
data=(work/'paradee_int8.onnx').read_bytes();adapted=adapt_model(data)
(work/'adapted.onnx').write_bytes(adapted)
spec={'id':'paradee-8m-v1.1-int8','title':'Paradee 超轻量英文','parameters':'7.88M',
 'archive':'paradee_int8.onnx','directory':'paradee-8m-v1.1-int8','kind':'paradee-onnx',
 'url':'https://huggingface.co/sahilmahendrakar/Paradee-8M-v1.1/resolve/'+(work/'revision.txt').read_text().strip()+'/onnx/paradee_int8.onnx',
 'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'sampleRate':24000,
 'source':'https://github.com/sahilmahendrakar/paradee','lexicons':['lexicon.txt'],'ruleFsts':[],
 'required':{'model.int8.onnx':len(adapted),'tokens.txt':len(tokens.encode()),'voices.bin':2048,'lexicon.txt':len(lexicon)},
 'adaptedSha256':hashlib.sha256(adapted).hexdigest(),
 'voices':[{'id':0,'name':'英文女声 · af_heart','code':'af_heart','language':'en'}]}
(assets/'paradee-model.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(root/'docs/licenses/Paradee-Apache-2.0.txt').write_bytes((work/'LICENSE').read_bytes())
(assets/'licenses/Paradee-Apache-2.0.txt').write_bytes((work/'LICENSE').read_bytes())
print(json.dumps(spec,ensure_ascii=False));print('Lexicon compressed bytes:',(assets/'paradee-lexicon.bin').stat().st_size)
