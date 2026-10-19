#!/usr/bin/env python3
"""Derive COMPARISON.md from the committed probe logs.

Two groups, kept apart on purpose. The isolated Hetzner ccx33 was idle and ran both builds twice,
so its milliseconds are comparable and are what the README and the runtime's javadoc quote. The
workstation runs the user's editor and screen recorder; its runs are kept because they were made,
and because the ratio inside each of them agrees with the isolated host, but its absolute times
are not comparable with anything.
"""

from __future__ import annotations

import re
from pathlib import Path

HERE = Path(__file__).resolve().parent

GROUPS = [
    ("Isolated host: Hetzner ccx33, 8 dedicated vCPU EPYC-Milan, idle", [
        ("vps/vps-0161-pass1.log", "0.1.61 pass 1"),
        ("vps/vps-0161-pass2.log", "0.1.61 pass 2"),
        ("vps/vps-0162-pass1.log", "0.1.62 pass 1"),
        ("vps/vps-0162-pass2.log", "0.1.62 pass 2"),
    ]),
    ("Workstation, contended: not comparable between runs", [
        ("released.log", "0.1.61, earlier A"),
        ("released-repeat.log", "0.1.61, earlier B"),
        ("released-heap.log", "0.1.61, same probe"),
        ("two-point-candidate.log", "two points, superseded"),
        ("adaptive-heap.log", "0.1.62, same probe"),
        ("adaptive-under-load.log", "0.1.62, contended"),
    ]),
]

SHAPES = [
    "one rubric, 1 question",
    "one rubric, 2 questions",
    "one rubric, 3 questions",
    "three rubrics, 1 question",
    "three rubrics, 2 questions",
    "three rubrics, 3 questions",
    "no rubric, 3 questions",
]

RATIOS = {
    "three rubrics: 3 questions / 1 question":
        ("three rubrics, 3 questions", "three rubrics, 1 question"),
    "one rubric: 3 questions / 1 question":
        ("one rubric, 3 questions", "one rubric, 1 question"),
    "cost of each question after the first, three rubrics": None,
}


def read(path: Path):
    text = path.read_text()
    millis, heap = {}, {}
    for m in re.finditer(r"^(\D.*?)\s{2,}(\d+)\s+(\d+)(?:\s+(\d+))?\s*$", text, re.M):
        label = m.group(1).strip()
        if label.startswith(("BATCH", "DISCARDED")):
            continue
        millis[label] = int(m.group(2))
        if m.group(4):
            heap[label] = int(m.group(4))
    load = re.search(r"load at start: \{? ?([\d.]+)", text)
    # The warm-up batch is discarded for latency and has to be discarded here too: its single
    # answer is the process's first forward pass, and it moves by up to 0.008 between two runs of
    # the same build on the isolated host while every measured answer stays put.
    measured = text[text.index("BATCH") :] if "BATCH" in text else text
    verdicts = re.findall(r"p=([\d.]+)  confidence=([\d.]+)", measured)
    warmup = re.findall(r"p=([\d.]+)  confidence=([\d.]+)", text[: text.find("BATCH")])
    return millis, heap, verdicts, load.group(1) if load else "not recorded", warmup


lines = []
for title, runs in GROUPS:
    data = {label: read(HERE / name) for name, label in runs if (HERE / name).exists()}
    if not data:
        continue
    present = [label for _, label in runs if label in data]
    lines += [f"## {title}", "",
              "| batch | " + " | ".join(present) + " |",
              "| --- | " + " | ".join("---" for _ in present) + " |"]
    for shape in SHAPES:
        cells = [f"{data[l][0][shape]:,} ms" if shape in data[l][0] else "-" for l in present]
        lines.append(f"| {shape} | " + " | ".join(cells) + " |")

    lines += ["", "| | " + " | ".join(present) + " |",
              "| --- | " + " | ".join("---" for _ in present) + " |",
              "| load average at start | " + " | ".join(data[l][3] for l in present) + " |"]
    heaps = []
    for label in present:
        values = set(data[label][1].values())
        heaps.append(f"{min(values)}-{max(values)} MiB" if len(values) > 1
                     else f"{next(iter(values))} MiB" if values else "not reported")
    lines.append("| live heap after a collection | " + " | ".join(heaps) + " |")

    for name, pair in RATIOS.items():
        cells = []
        for label in present:
            millis = data[label][0]
            if pair is None:
                one, three = "three rubrics, 1 question", "three rubrics, 3 questions"
                cells.append(f"**{(millis[three] - millis[one]) / 2:,.0f} ms**"
                             if one in millis and three in millis else "-")
            else:
                top, bottom = pair
                cells.append(f"**{millis[top] / millis[bottom]:.2f}x**"
                             if top in millis and bottom in millis else "-")
        lines.append(f"| {name} | " + " | ".join(cells) + " |")

    base = present[0]
    lines += ["", f"Measured verdicts, {len(data[base][2])} probability/confidence pairs, "
                  f"against {base} (the discarded warm-up batch is excluded):", ""]
    for label in present[1:]:
        pairs = data[label][2]
        if len(pairs) != len(data[base][2]):
            lines.append(f"- {label}: {len(pairs)} pairs recorded, not comparable")
        else:
            lines.append(f"- {label}: **{'identical' if pairs == data[base][2] else 'DIFFERENT'}**")
    warmups = {label: data[label][4] for label in present if data[label][4]}
    if len(warmups) > 1:
        values = sorted(float(v[0][0]) for v in warmups.values())
        lines.append("")
        spread = values[-1] - values[0]
        lines.append(
            f"The discarded warm-up answer ranges {values[0]:.6f} to {values[-1]:.6f} across these "
            f"runs, a spread of {spread:.6f}. It is the process's first forward pass, which is why "
            "it is discarded." + (" On the isolated host it moves between two runs of the same "
            "build as much as it does between builds." if spread else ""))
    lines.append("")

(HERE / "COMPARISON.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines[:26]))
