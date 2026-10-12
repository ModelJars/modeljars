"""Generate independent logits using the pinned publisher implementation, never Models."""

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    directory = args.directory.resolve()
    revision = "afda132cad380ac47da5ef055f186884d1c12f65"
    expected = {
        "model.safetensors": "1a54d8eb2adf19c296a1c129f9cd7d7395c21f5facfc6d13d2945e002d55e37d",
        "config.json": "0eb5d6e97360bf2480a4c45ed263f8d85e361b65db73ced1d25494caf4c3015d",
        "modeling_mobilemoe.py": "3787c8481f94a1aed652695031865c5696dbfaaa7f0e6a208ecc0aa81b65fde9",
        "configuration_mobilemoe.py": "00d11a52e3fcfdb674bc623341cb6eaefbb70b77cb5e337dabf40bc354bf29f5",
    }
    from huggingface_hub import hf_hub_download

    for name, digest in expected.items():
        path = directory / name
        if not path.exists():
            hf_hub_download("facebook/MobileMoE-S-QAT", name, revision=revision,
                            local_dir=directory, token=os.environ.get("HF_TOKEN"))
        with path.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != digest:
            raise ValueError(f"Publisher fixture checksum mismatch: {name}")

    # The pinned publisher source imports its configuration as a top-level module.
    sys.path.insert(0, str(directory))
    import torch
    from transformers import AutoModelForCausalLM

    torch.set_num_threads(min(4, os.cpu_count() or 1))
    torch.set_num_interop_threads(1)
    model = AutoModelForCausalLM.from_pretrained(
        directory, dtype=torch.bfloat16, local_files_only=True, trust_remote_code=True
    ).eval()
    outputs = {}
    with torch.inference_mode():
        for name, tokens in [("bos", [128000]), ("bos_hello", [128000, 9906])]:
            logits = model(input_ids=torch.tensor([tokens]), use_cache=True).logits[0, -1]
            values = logits.float().cpu().numpy().astype("<f4")
            path = directory / f"{name}.f32le"
            path.write_bytes(values.tobytes())
            outputs[path.name] = {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                                  "tokens": tokens, "count": len(values),
                                  "argmax": int(values.argmax())}
    report = {"publisherRevision": revision, "inputs": expected, "outputs": outputs,
              "packages": {name: importlib.metadata.version(name) for name in
                           ["torch", "transformers", "tokenizers", "safetensors", "numpy"]}}
    (directory / "oracle-provenance.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
