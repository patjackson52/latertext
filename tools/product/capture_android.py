#!/usr/bin/env python3
"""Capture local, synthetic Product Atlas fixtures on a fresh disposable emulator.

Requires native-capable Shipyard Python tooling. See native-capture.md. Never publishes.
The app must be absent before this command; this command installs then removes its own
APK/test APK. A clean exact commit is built locally; no existing APK is trusted.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.patjackson.latertext.debug"
TEST_PACKAGE = PACKAGE + ".test"
AVD = "latertext_atlas_api35"
TEST_CLASS = "com.patjackson.latertext.ProductAtlasCaptureTest#captureSyntheticMatrixRow"


class CaptureError(RuntimeError):
    pass


def run(*args: str, binary: bool = False, cwd: Path = ROOT) -> str | bytes:
    try:
        result = subprocess.run(args, cwd=cwd, capture_output=True, check=False, timeout=180 if "instrument" in args else 60)
    except (OSError, subprocess.TimeoutExpired):
        raise CaptureError(f"Command unavailable or timed out: {Path(args[0]).name}") from None
    if result.returncode:
        # Do not expose arbitrary device output or raw accessibility/application errors.
        raise CaptureError(f"Command failed: {Path(args[0]).name} {args[1] if len(args) > 1 else ''}")
    return result.stdout if binary else result.stdout.decode("utf-8").strip()


def clean_commit(root: Path = ROOT) -> str:
    if run("git", "status", "--porcelain", "--untracked-files=all", cwd=root):
        raise CaptureError("Capture requires a clean committed source tree")
    commit = run("git", "rev-parse", "HEAD", cwd=root)
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise CaptureError("Capture requires a full Git commit SHA")
    return commit


def new_output_path(path: Path, root: Path = ROOT) -> Path:
    root = root.resolve()
    output = path.expanduser().resolve()
    if output == root or root in output.parents or output.exists():
        raise CaptureError("Output must be a new directory outside the repository")
    return output


def device_guard(adb, serial: str, expected_avd: str, *, require_uninstalled: bool) -> None:
    if not re.fullmatch(r"emulator-[0-9]+", serial) or expected_avd != AVD:
        raise CaptureError(f"Only an explicitly selected disposable {AVD} emulator is allowed")
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise CaptureError("Device is not an Android emulator")
    if adb("emu", "avd", "name").splitlines()[0] != expected_avd:
        raise CaptureError("Emulator AVD identity does not match the disposable fixture")
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise CaptureError("Disposable emulator is not booted")
    if adb("shell", "getprop", "ro.build.version.sdk") != "35":
        raise CaptureError("Capture fixture requires API 35")
    for command, expected in (("size", "1080x2400"), ("density", "420")):
        if adb("shell", "wm", command).splitlines()[-1].split(": ")[-1] != expected:
            raise CaptureError("Disposable emulator geometry does not match the manifest")
    if require_uninstalled:
        for package in (PACKAGE, TEST_PACKAGE):
            if "package:" + package in adb("shell", "pm", "list", "packages", package).splitlines():
                raise CaptureError("A fresh emulator with both LaterText packages absent is required")


def producer_contract(manifest: dict) -> None:
    """Only the implemented synthetic fixture matrix may reach the instrumented producer."""
    if manifest["fixture_schema_version"] != "latertext_synthetic_android_v1":
        raise CaptureError("Manifest fixture version is not implemented by this producer")
    profiles = {profile["id"]: profile for profile in manifest["profiles"]}
    if set(profiles) != {"phone_normal", "phone_font200"}:
        raise CaptureError("Manifest profiles are not implemented by this producer")
    for name, scale in (("phone_normal", 1_000_000), ("phone_font200", 2_000_000)):
        profile = profiles[name]
        expected = dict(width_px=1080, height_px=2400, density_dpi=420, font_scale_ppm=scale)
        if any(profile[key] != value for key, value in expected.items()):
            raise CaptureError("Manifest geometry differs from the implemented fixture")
    targets = {target["id"]: target for target in manifest["targets"]}
    expected_targets = {
        "empty_upcoming": ("component_latertext_upcoming_content", "surface_latertext_upcoming", "state_latertext_upcoming_empty", "latertext_empty_upcoming"),
        "blank_composer": ("component_latertext_composer_content", "surface_latertext_composer", "state_latertext_composer_blank", "latertext_blank_composer"),
        "schedule_editor": ("component_latertext_schedule_editor_content", "surface_latertext_schedule_editor", "state_latertext_schedule_editing", "latertext_schedule_editor"),
    }
    if set(targets) != set(expected_targets):
        raise CaptureError("Manifest targets are not implemented by this producer")
    for name, expected in expected_targets.items():
        if tuple(targets[name][key] for key in ("entity_field", "surface_field", "state_field", "fixture_id")) != expected:
            raise CaptureError("Manifest target does not describe the implemented fixture")


def sha256(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-avd", required=True, choices=[AVD])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args(argv)
    output = new_output_path(args.output)
    commit = clean_commit()
    # Import and validate before any install or settings mutation. Old tooling fails closed.
    from shipyard.contracts.product_definition import load_definition
    from shipyard.product_tooling.capture import validate_adapter_manifest
    from shipyard.product_tooling.generated_ids import field_name
    from shipyard.product_tooling.native_capture import validate_android_capture_files

    definition = load_definition((ROOT / ".product/product-definition.json").read_bytes())
    manifest = validate_adapter_manifest(json.loads((ROOT / ".product/product-capture-adapter.json").read_text()), definition)
    producer_contract(manifest)
    fields = {field_name(entity.id): entity.id for entity in definition.entities}
    adb = lambda *command, binary=False: run(args.adb, "-s", args.serial, *command, binary=binary)
    device_guard(adb, args.serial, args.expected_avd, require_uninstalled=True)
    original_font = adb("shell", "settings", "get", "system", "font_scale")
    if original_font != "null" and not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", original_font):
        raise CaptureError("Cannot safely restore the original font scale")
    # A generated-ID drift check precedes the build; it never changes source files.
    run(sys.executable, "scripts/generate_product_ids.py", "--check")
    java_home = os.environ.get("JAVA_HOME")
    java = Path(java_home or "/invalid") / "bin/java"
    if not java.is_file():
        raise CaptureError("Set JAVA_HOME to JDK 17")
    result = subprocess.run([str(java), "-version"], capture_output=True, timeout=10)
    if result.returncode or b'version "17.' not in result.stderr + result.stdout:
        raise CaptureError("Set JAVA_HOME to JDK 17")
    output.mkdir(parents=True)
    tasks = [":app:assembleDebug", ":app:assembleDebugAndroidTest"]
    with (output / "build.log").open("wb") as log:
        result = subprocess.run([str(ROOT / "gradlew"), *tasks, "--console=plain"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    if result.returncode:
        raise CaptureError("Capture build failed; inspect the local build.log")
    if clean_commit() != commit:
        raise CaptureError("Source changed during the build")
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    apk_hash, test_hash = sha256(apk.read_bytes()), sha256(test_apk.read_bytes())
    build_id = "latertext-debug-" + hashlib.sha256((commit + apk_hash + test_hash).encode()).hexdigest()
    provenance = dict(git_commit_sha=commit, definition_digest=manifest["definition_digest"], build_id=build_id,
                      apk_sha256=apk_hash, test_apk_sha256=test_hash, package_name=PACKAGE,
                      emulator_avd=AVD, build_tasks=tasks)
    (output / "build.json").write_text(json.dumps(provenance, indent=2) + "\n")
    installed = []
    captures = []
    app_version = None
    try:
        device_guard(adb, args.serial, args.expected_avd, require_uninstalled=True)
        for package, path in ((PACKAGE, apk), (TEST_PACKAGE, test_apk)):
            # Initial absence gives this invocation exclusive cleanup ownership even if install fails.
            installed.append(package)
            if "Success" not in adb("install", str(path)):
                raise CaptureError("Installing the locally built fixture APK failed")
        for profile in manifest["profiles"]:
            device_guard(adb, args.serial, args.expected_avd, require_uninstalled=False)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "settings", "put", "system", "font_scale", str(profile["font_scale_ppm"] / 1_000_000))
            result = adb("shell", "am", "instrument", "-w", "-r",
                         "-e", "class", TEST_CLASS,
                         "-e", "latertextAtlasDisposable", "true",
                         "-e", "latertextAtlasProfile", profile["id"],
                         "-e", "latertextAtlasCommit", commit,
                         "-e", "latertextAtlasApkSha256", apk_hash,
                         TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner")
            if "OK (1 test)" not in result or "FAILURES" in result or "INSTRUMENTATION_FAILED" in result:
                raise CaptureError("Synthetic instrumentation failed; no capture bundle was completed")
            local_profile = output / profile["id"]
            local_profile.mkdir()
            def pull(name):
                data = adb("exec-out", "run-as", PACKAGE, "cat", f"cache/product-atlas/{profile['id']}/{name}", binary=True)
                (local_profile / name).write_bytes(data)
                return data
            attestation = json.loads(pull("attestation.json"))
            expected = {"git_commit_sha": commit, "definition_digest": manifest["definition_digest"],
                        "apk_sha256": apk_hash, "package_name": PACKAGE,
                        "density_dpi": profile["density_dpi"], "font_scale_ppm": profile["font_scale_ppm"]}
            if any(attestation.get(key) != value for key, value in expected.items()):
                raise CaptureError("Installed APK or runtime profile failed its attestation")
            if app_version is not None and app_version != attestation["app_version"]:
                raise CaptureError("App version changed within capture matrix")
            app_version = attestation["app_version"]
            for target in manifest["targets"]:
                data = pull(target["id"] + ".png")
                hierarchy = json.loads(pull(target["id"] + ".json"))
                captures.append(dict(
                    capture_key=profile["id"] + "." + target["id"], device_profile=profile["device_profile"],
                    profile_id=profile["id"], viewport={key: profile[key] for key in ("width_px", "height_px", "density_dpi", "font_scale_ppm")},
                    locale=attestation["locale"], theme="light", surface_id=fields[target["surface_field"]],
                    state_id=fields[target["state_field"]] if target["state_field"] else None, fixture_id=target["fixture_id"],
                    screenshot=dict(path=profile["id"] + "/" + target["id"] + ".png", width_px=profile["width_px"],
                                    height_px=profile["height_px"], sha256=sha256(data), bytes=len(data)), hierarchy_source=hierarchy))
        if clean_commit() != commit:
            raise CaptureError("Source changed during the capture")
        bundle = dict(schema_version="product-android-capture-source-bundle/v1",
                      adapter=dict(name=manifest["adapter_name"], version=manifest["adapter_version"]),
                      definition_digest=manifest["definition_digest"], git_commit_sha=commit, build_id=build_id,
                      app_version=app_version, platform="android", generated_at=datetime.now(timezone.utc).isoformat(), captures=captures)
        validate_android_capture_files(bundle, manifest, definition, capture_root=output, expected_git_commit_sha=commit)
    finally:
        cleanup_errors = []
        for package in reversed(installed):
            try:
                adb("uninstall", package)
            except CaptureError:
                cleanup_errors.append("uninstall " + package)
        try:
            if original_font == "null":
                adb("shell", "settings", "delete", "system", "font_scale")
            else:
                adb("shell", "settings", "put", "system", "font_scale", original_font)
        except CaptureError:
            cleanup_errors.append("restore font scale")
        if cleanup_errors:
            raise CaptureError("Disposable emulator cleanup requires attention: " + ", ".join(cleanup_errors))
    (output / "source-bundle.json").write_text(json.dumps(bundle, indent=2) + "\n")
    print(f"Validated {len(captures)} local synthetic captures at {commit}: {output / 'source-bundle.json'}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (CaptureError, ImportError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f"Capture stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
