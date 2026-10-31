"""Replay recorded prompts in pinned Ollama with the identical SHA-verified GGUF."""
import hashlib
import json
import pathlib
import subprocess
import sys
import urllib.request

report_path, cache_path = map(pathlib.Path, sys.argv[1:])
report = json.loads(report_path.read_text())
job = report['job']
files = [p for p in cache_path.rglob('*') if p.is_file() and p.stat().st_size == job['sizeBytes']]
assert len(files) == 1, files
model = files[0]
with model.open('rb') as f:
    assert hashlib.file_digest(f, 'sha256').hexdigest() == job['sha256']
modelfile = report_path.with_suffix('.Modelfile')
modelfile.write_text('FROM ' + str(model) + '\n')
ollama = ['runuser', '-u', 'root', '--', 'ollama']
subprocess.run(ollama + ['create', 'quality-probe', '-f', str(modelfile)], check=True, timeout=600)
out = dict(model=job['id'], artifactSha256=job['sha256'], ollamaVersion=subprocess.check_output(ollama+['--version'],text=True).strip(), cases=[])
target = report_path.with_suffix('.reference.json')
try:
    for c in report['cases']:
        data = dict(model='quality-probe', prompt=c['prompt'], raw=True, stream=False, keep_alive='10m',
                    options=dict(temperature=0,top_k=1,top_p=1,repeat_penalty=1,seed=42,num_predict=job['maxTokens'],num_ctx=2048,num_thread=8,num_gpu=0))
        request = urllib.request.Request('http://127.0.0.1:11434/api/generate',data=json.dumps(data).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=600) as response:
            result=json.load(response)
        out['cases'].append(dict(caseId=c['caseId'],mode=c['mode'],prompt=c['prompt'],expected=c['expected'],candidateRaw=c['raw'],candidateVisible=c['visible'],rawEqual=result['response']==c['raw'],reference=result))
        target.write_text(json.dumps(out,indent=2)+'\n')
        print(job['id'],c['mode'],c['caseId'],'rawEqual='+str(result['response']==c['raw']),flush=True)
    out['complete']=True
finally:
    target.write_text(json.dumps(out,indent=2)+'\n')
    subprocess.run(ollama+['stop','quality-probe'],timeout=60)
    subprocess.run(ollama+['rm','quality-probe'],timeout=60)
