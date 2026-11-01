#!/usr/bin/env python3
"""Triage Paradee-8M at a pinned Hugging Face revision.

`npm run catalog:triage` reads GGUF headers. Paradee publishes no GGUF, so this is the
equivalent for an ONNX artifact: it pins the revision, re-derives every digest and byte
size from the downloaded bytes, and prints the graph inventory that decides whether the
runtime can serve the model. Nothing here is a quality or speed measurement.

    uv run --with onnx docs/model-evidence/paradee-8m/triage.py --version 1.2 > TRIAGE-v1.2.md
    uv run --with onnx docs/model-evidence/paradee-8m/triage.py --version 1.1 > TRIAGE-v1.1.md
"""

import argparse
import collections
import hashlib
import json
import pathlib
import urllib.request

# One pinned revision per published version, so a later run cannot drift onto a different artifact.
PINNED = {
    "1.1": ("sahilmahendrakar/Paradee-8M-v1.1", "330617ad1f097b4f6b02c103e54b39006c090b45"),
    "1.2": ("sahilmahendrakar/Paradee-8M-v1.2", "ed2934d4df8a4fd891e29584c5e1104649409765"),
}
FILES = [
    "config.json",
    "tokenizer.json",
    "onnx/paradee.onnx",
    "onnx/paradee_int8.onnx",
    "pytorch/text_side.pt",
    "pytorch/decoder.pt",
]
CONFIG_KEYS = ("model_type", "version", "sample_rate", "n_token", "voices", "teacher", "teacher_voice")
# Operators the Java/Rust audio runtime does not implement. Checked by name against
# models-audio and backend-java on 2026-11-01; every one of them was absent.
UNSERVED = {"LSTM", "InstanceNormalization", "ConvTranspose", "Resize"}


def fetch(cache: pathlib.Path, repo: str, revision: str, name: str) -> pathlib.Path:
    target = cache / revision / name.replace("/", "_")
    if not target.exists():
        url = f"https://huggingface.co/{repo}/resolve/{revision}/{name}"
        target.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, target)
    return target


def digest(path: pathlib.Path) -> tuple[str, int]:
    sha, size = hashlib.sha256(), 0
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            sha.update(chunk)
            size += len(chunk)
    return sha.hexdigest(), size


def inventory(path: pathlib.Path) -> dict:
    import onnx
    from onnx import numpy_helper

    model = onnx.load(str(path), load_external_data=False)
    graph = model.graph
    ops = collections.Counter(node.op_type for node in graph.node)
    elements = collections.Counter()
    for initializer in graph.initializer:
        array = numpy_helper.to_array(initializer)
        elements[str(array.dtype)] += int(array.size)

    def shape(value):
        dims = value.type.tensor_type.shape.dim
        return [d.dim_param or d.dim_value for d in dims]

    initializer_names = {i.name for i in graph.initializer}
    return {
        "irVersion": model.ir_version,
        "producer": f"{model.producer_name} {model.producer_version}".strip(),
        "opset": {(o.domain or "ai.onnx"): o.version for o in model.opset_import},
        "inputs": [
            (v.name, onnx.TensorProto.DataType.Name(v.type.tensor_type.elem_type), shape(v))
            for v in graph.input
            if v.name not in initializer_names
        ],
        "outputs": [
            (v.name, onnx.TensorProto.DataType.Name(v.type.tensor_type.elem_type), shape(v))
            for v in graph.output
        ],
        "nodes": len(graph.node),
        "distinctOps": len(ops),
        "ops": dict(ops.most_common()),
        "initializerElements": sum(elements.values()),
        "initializerByDtype": dict(elements.most_common()),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", choices=sorted(PINNED), default="1.2")
    parser.add_argument("--cache", type=pathlib.Path, default=pathlib.Path(".paradee-triage"))
    arguments = parser.parse_args()
    repo, revision = PINNED[arguments.version]
    arguments.cache.mkdir(parents=True, exist_ok=True)

    print(f"# Paradee-8M v{arguments.version} triage\n\n`{repo}` at `{revision}`\n")
    print("| file | bytes | sha256 |")
    print("| --- | --- | --- |")
    graphs = {}
    for name in FILES:
        path = fetch(arguments.cache, repo, revision, name)
        sha, size = digest(path)
        print(f"| `{name}` | {size:,} | `{sha}` |")
        if name.endswith(".onnx"):
            graphs[name] = path
    config = json.loads(fetch(arguments.cache, repo, revision, "config.json").read_text())
    print()
    print("| config key | value |")
    print("| --- | --- |")
    for key in CONFIG_KEYS:
        if key in config:
            print(f"| `{key}` | `{config[key]}` |")
    print(f"| `vocab` | `{len(config.get('vocab', {}))} phoneme symbols` |")

    for name, path in graphs.items():
        data = inventory(path)
        print(f"\n## `{name}`\n")
        print(f"- ir_version {data['irVersion']}, producer {data['producer']}, opset {data['opset']}")
        for label in ("inputs", "outputs"):
            rendered = ", ".join(f"{n}:{t}{s}" for n, t, s in data[label])
            print(f"- {label}: {rendered}")
        print(f"- {data['nodes']} nodes, {data['distinctOps']} distinct operators")
        print(f"- initializer elements {data['initializerElements']:,} by dtype {data['initializerByDtype']}")
        missing = {op: count for op, count in data["ops"].items() if op in UNSERVED}
        print(f"- operators the audio runtime does not implement: {missing or 'none'}")
        nondeterministic = {
            op: count for op, count in data["ops"].items() if op.startswith("Random")
        }
        print(f"- nondeterministic operators: {nondeterministic or 'none'}")
        print(f"- full operator histogram: {data['ops']}")


if __name__ == "__main__":
    main()
