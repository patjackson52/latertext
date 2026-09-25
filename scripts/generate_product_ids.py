#!/usr/bin/env python3
"""Generate/check Kotlin and tool-facing IDs with native-capable Shipyard tooling."""
import argparse
import json
from pathlib import Path

from shipyard.contracts.product_definition import load_definition
from shipyard.domain.product_definition.normalize import definition_digest
from shipyard.product_tooling.generated_ids import (
    generated_outputs_current,
    render_javascript,
    render_kotlin,
    render_python,
    unchecked_product_literals,
)

ROOT = Path(__file__).resolve().parents[1]
DEFINITION = ROOT / ".product/product-definition.json"
PYTHON_IDS = ROOT / "tools/product/product_ids.py"
JAVASCRIPT_IDS = ROOT / "tools/product/product-ids.mjs"
KOTLIN_IDS = ROOT / "app/src/main/java/com/patjackson/latertext/product/ProductIds.kt"
KOTLIN_PACKAGE = "com.patjackson.latertext.product"
ASSOCIATIONS = ROOT / ".product/product-association-sources.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    definition = load_definition(DEFINITION.read_bytes())
    digest = definition_digest(definition)
    associations = json.loads(ASSOCIATIONS.read_text())
    if args.write:
        PYTHON_IDS.parent.mkdir(parents=True, exist_ok=True)
        PYTHON_IDS.write_text(render_python(definition))
        JAVASCRIPT_IDS.write_text(render_javascript(definition))
        KOTLIN_IDS.parent.mkdir(parents=True, exist_ok=True)
        KOTLIN_IDS.write_text(render_kotlin(definition, KOTLIN_PACKAGE))
        associations["definition_digest"] = digest
        ASSOCIATIONS.write_text(json.dumps(associations, indent=2) + "\n")
        capture = ROOT / ".product/product-capture-adapter.json"
        adapter = json.loads(capture.read_text())
        adapter["definition_digest"] = digest
        capture.write_text(json.dumps(adapter, indent=2) + "\n")
        print("Generated Kotlin/Python/JavaScript IDs and manifest digests.")
        return 0
    current, diagnostics = generated_outputs_current(
        definition_path=DEFINITION,
        python_output=PYTHON_IDS,
        javascript_output=JAVASCRIPT_IDS,
        kotlin_output=KOTLIN_IDS,
        kotlin_package=KOTLIN_PACKAGE,
    )
    if associations["definition_digest"] != digest:
        current = False
        diagnostics += ("stale_association_digest",)
    if not current:
        print("Stale Product Definition artifacts:", ", ".join(diagnostics))
        return 1
    findings = unchecked_product_literals(
        scan_roots=tuple(ROOT / module / "src" for module in ("app", "feature", "platform")),
        generated_outputs=(PYTHON_IDS, JAVASCRIPT_IDS, KOTLIN_IDS),
        entity_ids=frozenset(entity.id for entity in definition.entities),
    )
    if findings:
        for path, line, entity_id in findings:
            print(f"Unchecked Product ID literal: {path}:{line}: {entity_id}")
        return 1
    print("Kotlin/tool-facing IDs and association digest are current:", digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
