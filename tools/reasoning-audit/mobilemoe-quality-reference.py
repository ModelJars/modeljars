"""Compare chat outputs with the pinned publisher implementation on the authenticated runner."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

p = argparse.ArgumentParser()
p.add_argument('--cache', type=Path, required=True)
p.add_argument('--report', type=Path, required=True)
a = p.parse_args()
weights = list(a.cache.rglob('model.safetensors'))
assert len(weights) == 1, weights
directory = weights[0].parent
# This existing helper downloads and hashes the pinned publisher code and weights.
subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]/'prepare-mobilemoe-oracle.py'), '--directory', str(directory)],check=True)
sys.path.insert(0,str(directory))
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
torch.set_num_threads(min(4,os.cpu_count() or 1))
torch.set_num_interop_threads(1)
model=AutoModelForCausalLM.from_pretrained(directory,dtype=torch.bfloat16,local_files_only=True,trust_remote_code=True).eval()
tokenizer=AutoTokenizer.from_pretrained(directory,local_files_only=True,trust_remote_code=True)
report=json.loads(a.report.read_text())
out=dict(publisherProvenance=json.loads((directory/'oracle-provenance.json').read_text()),cases=[])
target=a.report.with_suffix('.reference.json')
with torch.inference_mode():
    for c in report['cases']:
        ids=c['promptTokenIds']
        encoded=tokenizer.encode(c['prompt'],add_special_tokens=False)
        # Keep both encodings: a mismatch is a tokenizer finding, not hidden by the reference.
        x=torch.tensor([ids])
        y=model.generate(input_ids=x,attention_mask=torch.ones_like(x),do_sample=False,max_new_tokens=512,pad_token_id=tokenizer.eos_token_id)
        tokens=y[0,len(ids):].tolist()
        answer=tokenizer.decode(tokens,skip_special_tokens=True)
        out['cases'].append(dict(caseId=c['caseId'],mode=c['mode'],promptTokenIds=ids,publisherEncodedPrompt=encoded,tokenizerEqual=encoded==ids,expected=c['expected'],candidateRaw=c['raw'],referenceRaw=answer,referenceTokens=tokens,rawEqual=answer==c['raw']))
        target.write_text(json.dumps(out,indent=2)+'\n')
out['complete']=True
target.write_text(json.dumps(out,indent=2)+'\n')
