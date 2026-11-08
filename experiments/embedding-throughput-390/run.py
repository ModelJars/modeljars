#!/usr/bin/env python3
"""Run only on the dedicated Linux worker with two published SDK distributions."""

import argparse
import json
import pathlib
import platform
import statistics
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=pathlib.Path, required=True)
    parser.add_argument("--old", type=pathlib.Path, required=True)
    parser.add_argument("--new", type=pathlib.Path, required=True)
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--cache", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    config = parser.parse_args()
    if platform.system() != "Linux":
        raise SystemExit("Model execution is restricted to the Linux benchmark worker")
    config.output.mkdir(parents=True, exist_ok=False)
    cases = json.loads(config.manifest.read_text())

    def run(arm, case, label, mode="measure"):
        distribution = config.old if arm == "old" else config.new
        output = config.output / f"{label}.json"
        command = [
            str(config.java),
            "--add-modules=jdk.incubator.vector",
            "--enable-native-access=ALL-UNNAMED",
            "-Xmx4g",
            "-Dvectors.gguf.threads=8",
            "-Dmodels.native.kernels.threads=8",
            "-cp",
            str(distribution / "lib/*"),
            "ReleasedEmbeddingConsumer",
            str(config.manifest),
            case,
            str(config.cache),
            str(output),
            mode,
        ]
        (config.output / f"{label}-command.json").write_text(
            json.dumps(command, indent=2) + "\n"
        )
        print("START", label, flush=True)
        with (config.output / f"{label}.log").open("w") as log:
            subprocess.run(
                command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600
            )
        print("DONE", label, flush=True)
        return json.loads(output.read_text()) if mode == "measure" else None

    for model in ["eg2", "eg300"]:
        run("old", f"{model}-32", f"install-{model}", "install")

    fidelity = {}
    for case in [name for name in cases if "-oracle-" in name]:
        old = run("old", case, f"{case}-old")
        new = run("new", case, f"{case}-new")
        fidelity[case] = {
            "oldCosine": old["oracleCosine"],
            "newCosine": new["oracleCosine"],
            "bitIdentical": old["vectorBitsSha256"] == new["vectorBitsSha256"],
        }

    comparisons = {}
    for case in [name for name in cases if "-oracle-" not in name]:
        reports = [
            run("old", case, f"{case}-old-before"),
            run("new", case, f"{case}-new"),
            run("old", case, f"{case}-old-after"),
        ]
        samples = [
            [row["milliseconds"] for row in report["runs"] if row["phase"] == "measured"]
            for report in reports
        ]
        before, candidate, after = [statistics.median(values) for values in samples]
        drift = abs(before - after)
        gain = min(before, after) - candidate
        comparisons[case] = {
            "oldBeforeMedianMs": before,
            "newMedianMs": candidate,
            "oldAfterMedianMs": after,
            "baselineDriftMs": drift,
            "conservativeGainMs": gain,
            "gainExceedsDrift": gain > drift,
            "conservativeSpeedup": min(before, after) / candidate,
            "bitIdentical": len({r["vectorBitsSha256"] for r in reports}) == 1,
        }
        (config.output / "summary.json").write_text(
            json.dumps({"fidelity": fidelity, "latency": comparisons}, indent=2) + "\n"
        )


if __name__ == "__main__":
    main()
