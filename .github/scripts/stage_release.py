"""Store verified build files in an unpublished release without Actions storage."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

import publish_latest as publisher

SPECS = {
    "android": [("howl-apk", "*universal*.apk", "Howl.apk")],
    "windows": [("howl-setup", "Howl-Setup-*.exe", "Howl-Setup.exe"),
                ("howl-portable", "Howl-Portable-*.zip", "Howl-Portable.zip")],
}


def upload_draft(repo, run, assets, platform):
    tag = f"ci-{run['id']}-{run['run_attempt']}"
    existing = publisher.find_release(repo, tag)
    if existing is not None:
        publisher.verify_draft_identity(existing, run)
    if {path.name for path in assets.iterdir()} != {name for _, _, name in SPECS[platform]}:
        raise RuntimeError("Only the verified platform binaries may be staged")
    checksums = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(assets.iterdir())}
    if any(not p.stat().st_size for p in assets.iterdir()):
        raise RuntimeError("Cannot stage empty binary")
    (assets / "SHA256SUMS.txt").write_text(
        "".join(f"{digest}  {name}\n" for name, digest in checksums.items()), encoding="utf-8", newline="\n")
    if existing is None:
        publisher.gh("release", "create", tag, "--repo", repo, "--draft", "--target", run["head_sha"],
                     "--title", f"Howl {platform} - build {run['id']}",
                     "--notes", f"Verified build candidate. Source commit: {run['head_sha']}")
    # Recheck immediately before overwrite. Only this run's unpublished draft is mutable.
    publisher.verify_draft_identity(publisher.find_release(repo, tag), run)
    publisher.gh("release", "upload", tag, *sorted(assets.iterdir()), "--repo", repo, "--clobber")
    publisher.verify_assets(repo, tag, assets)
    with tempfile.TemporaryDirectory() as temp:
        download = Path(temp)
        publisher.download_draft(repo, run, SPECS[platform], download)
        if any((download / name).read_bytes() != (assets / name).read_bytes()
               for name in [*checksums, "SHA256SUMS.txt"]):
            raise RuntimeError("Downloaded draft differs from the verified build")
    print(f"Verified unpublished draft: {tag}")


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    platform = os.environ["BUILD_PLATFORM"]
    branch = {"android": "dev", "windows": "main"}[platform]
    run = publisher.api(f"repos/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}")
    if (os.environ["GITHUB_REF"] != f"refs/heads/{branch}" or run["head_branch"] != branch
            or run["head_repository"]["full_name"] != repo
            or run["event"] not in {"push", "workflow_dispatch"}
            or run["head_sha"] != os.environ["GITHUB_SHA"]
            or run["run_attempt"] != int(os.environ["GITHUB_RUN_ATTEMPT"])
            or run["path"] != ".github/workflows/build.yml"
            or run["status"] != "in_progress"):
        raise RuntimeError("Only this trusted platform production build can stage a draft")
    source = Path(os.environ["BUILD_OUTPUT"])
    with tempfile.TemporaryDirectory() as temp:
        assets = Path(temp)
        for _, pattern, filename in SPECS[platform]:
            shutil.copyfile(publisher.single_file(source, pattern), assets / filename)
        upload_draft(repo, run, assets, platform)


if __name__ == "__main__":
    main()
