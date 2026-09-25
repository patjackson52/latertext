#!/usr/bin/env python3
"""Read declared bindings from an exact commit; do not publish observations."""
import argparse
import json
import subprocess
from pathlib import Path

from shipyard.contracts.product_definition import load_definition
from shipyard.product_tooling.bindings import GitSource, check_bindings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", help="Full commit; defaults to local HEAD")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    commit = args.commit or subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
    ).strip()
    source = GitSource(root, commit)
    definition = load_definition(source.read(".product/product-definition.json"))
    report = check_bindings(
        repository_root=root, definition=definition, git_commit_sha=commit,
    )
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
