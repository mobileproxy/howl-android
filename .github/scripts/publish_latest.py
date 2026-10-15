"""Publish the newest successful trusted build as a durable GitHub Release."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def gh(*args):
    return subprocess.check_output(["gh", *map(str, args)], text=True).strip()


def api(path):
    return json.loads(gh("api", path))


def find_release(repo, tag):
    pages = json.loads(gh("api", f"repos/{repo}/releases?per_page=100",
                          "--paginate", "--slurp"))
    return next((r for page in pages for r in page if r["tag_name"] == tag), None)


def select_run(runs, branch, repo):
    eligible = [
        run for run in runs
        if run["conclusion"] == "success"
        and run["head_branch"] == branch
        and run["event"] in ("push", "workflow_dispatch")
        and run["head_repository"]["full_name"] == repo
    ]
    if not eligible:
        raise RuntimeError("No successful trusted build found")
    return max(eligible, key=lambda run: (run["run_number"], run["run_attempt"]))


def single_file(root, pattern):
    matches = [p for p in root.rglob(pattern) if p.is_file()]
    if len(matches) != 1 or matches[0].stat().st_size == 0:
        raise RuntimeError(f"Expected exactly one nonempty {pattern} in {root}")
    return matches[0]


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    branch = os.environ["BUILD_BRANCH"]
    platform = os.environ["BUILD_PLATFORM"]
    # Only the existing build workflow on our trusted branch may supply binaries.
    data = api(f"repos/{repo}/actions/workflows/build.yml/runs?branch={branch}&status=success&per_page=100")
    run = select_run(data["workflow_runs"], branch, repo)
    tag = f"ci-{run['id']}-{run['run_attempt']}"
    specs = {
        "windows": [("howl-setup", "*.exe", "Howl-Setup.exe"),
                    ("howl-portable", "*.zip", "Howl-Portable.zip")],
        "android": [("howl-apk", "*arm64-v8a*.apk", "Howl.apk")],
    }[platform]

    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        assets = root / "assets"
        assets.mkdir()
        for artifact, pattern, filename in specs:
            destination = root / artifact
            gh("run", "download", run["id"], "--repo", repo,
               "--name", artifact, "--dir", destination)
            shutil.copyfile(single_file(destination, pattern), assets / filename)
        checksums = {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(assets.iterdir())
        }
        (assets / "SHA256SUMS.txt").write_text(
            "".join(f"{digest}  {name}\n" for name, digest in checksums.items()),
            encoding="utf-8",
        )
        notes = root / "notes.md"
        notes.write_text(
            f"Latest successful {platform} CI build.\n\n"
            f"Source: {run['html_url']}\n\n"
            f"Commit: {run['head_sha']}\n\n"
            "These downloads do not expire with Actions artifacts. "
            "This is an automatic CI build, not a separate manual release approval.\n",
            encoding="utf-8",
        )
        # Find drafts too; do not turn an API outage into a duplicate release.
        existing = find_release(repo, tag)
        if existing is None:
            gh("release", "create", tag, "--repo", repo, "--draft",
               "--target", run["head_sha"], "--title", f"Howl {platform} - build {run['run_number']}",
               "--notes-file", notes)
        elif not existing["draft"]:
            verify_assets(repo, tag, assets)
            gh("release", "edit", tag, "--repo", repo, "--latest")
            print(f"Already published: https://github.com/{repo}/releases/latest")
            return
        # Only a draft can be retried/overwritten; the previous public release stays intact.
        gh("release", "upload", tag, *sorted(assets.iterdir()), "--repo", repo, "--clobber")
        verify_assets(repo, tag, assets)
        gh("release", "edit", tag, "--repo", repo, "--draft=false", "--latest")
        print(f"Published: https://github.com/{repo}/releases/latest")


def verify_assets(repo, tag, directory):
    # The tag endpoint returns 404 for drafts whose tag is not created yet.
    release = find_release(repo, tag)
    if release is None:
        raise RuntimeError(f"Release not found: {tag}")
    remote = {asset["name"]: asset for asset in release["assets"]}
    for path in directory.iterdir():
        asset = remote.get(path.name)
        digest = "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
        if not asset or asset["size"] != path.stat().st_size or asset.get("digest") != digest:
            raise RuntimeError(f"Release asset verification failed: {path.name}")


if __name__ == "__main__":
    main()
