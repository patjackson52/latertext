#!/usr/bin/env python3
"""Build a development APK with Shipyard metadata from pinned plugin source. Does not publish."""
import argparse
import io
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = "0597dd4fe6834dcac59ecf33f4e97be6496e013f"
VERSION = "0.1.0-latertext.0597dd4"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deploy-repo", type=Path, default=ROOT.parent / "shipyard-deploy")
    args = parser.parse_args()
    source = args.deploy_repo.resolve()
    if not (source / ".git").exists():
        parser.error("--deploy-repo must name a Shipyard Deploy checkout containing the pinned revision")
    archive = subprocess.check_output(["git", "-C", str(source), "archive", REVISION, "gradle-plugin"])
    cache = Path.home() / ".cache/latertext/shipyard-deploy" / REVISION
    cache.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        files.extractall(cache, filter="data")
    plugin = cache / "gradle-plugin"
    subprocess.run([str(plugin / "gradlew"), "publishAllPublicationsToTestRepoRepository",
                    "-PshipyardPluginVersion=" + VERSION, "--console=plain"], cwd=plugin, check=True)
    repository = (plugin / "build/test-repo").as_uri()
    subprocess.run([str(ROOT / "gradlew"), ":app:assembleDevelopment", "--console=plain",
                    "-PshipyardDeployPluginRepository=" + repository], cwd=ROOT, check=True)
    print(ROOT / "app/build/outputs/apk/development/app-development.apk")


if __name__ == "__main__":
    main()
