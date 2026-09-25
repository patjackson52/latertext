#!/usr/bin/env python3
"""Compare the committed consumer schema and generated sources with the pinned SWIP revision."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--swip-repo", type=Path, default=ROOT.parent / "sloopworksinstrumentationplatform")
    args = parser.parse_args()
    pin = json.loads((ROOT / ".swip/upstream.json").read_text())
    def upstream(path):
        return subprocess.check_output(["git", "-C", str(args.swip_repo), "show", pin["commit"] + ":" + path])
    files = list((ROOT / ".swip/schemas/latertext").glob("*.yaml")) + [ROOT / ".swip/registry/products/latertext.yaml"]
    for local in files:
        assert local.read_bytes() == upstream(str(local.relative_to(ROOT / ".swip"))), f"Schema drift: {local.name}"
    directory = "sdk-kmp/schema-latertext/src/commonMain/kotlin/works/sloop/swip/schema/latertext/"
    for local in (ROOT / "app/src/debug/java/works/sloop/swip/schema/latertext").glob("*.kt"):
        assert local.read_bytes() == upstream(directory + local.name), f"Generated source drift: {local.name}"
    print("SWIP schema and generated sources match", pin["commit"])


if __name__ == "__main__":
    main()
