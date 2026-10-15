"""Publish the newest successful trusted build as a durable GitHub Release."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from urllib.parse import quote


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


def can_promote_run(repo, run):
    """Do not replace a newer manual release with an older successful dev build."""
    pages = json.loads(gh("api", f"repos/{repo}/releases?per_page=100", "--paginate", "--slurp"))
    published = [r for page in pages for r in page if not r["draft"] and not r.get("prerelease")]
    if not published:
        return True
    # Compare every published source: a divergent or newer release needs review.
    # API errors deliberately propagate; an outage must not permit a downgrade.
    for release in published:
        base = quote(release["tag_name"], safe="")
        head = quote(run["head_sha"], safe="")
        comparison = api(f"repos/{repo}/compare/{base}...{head}")
        if comparison["status"] not in {"ahead", "identical"}:
            return False
    return True


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

    if not can_promote_run(repo, run):
        print("A newer or divergent release is already published; keeping it unchanged")
        return
    existing = find_release(repo, tag)
    if existing is not None and not existing["draft"]:
        finish_release(repo, tag, run, specs, platform)
        print(f"Already published: https://github.com/{repo}/releases/latest")
        return

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
        if existing is None:
            gh("release", "create", tag, "--repo", repo, "--draft",
               "--target", run["head_sha"], "--title", f"Howl {platform} - build {run['run_number']}",
               "--notes-file", notes)
        # Only a draft can be retried/overwritten; the previous public release stays intact.
        gh("release", "upload", tag, *sorted(assets.iterdir()), "--repo", repo, "--clobber")
        verify_assets(repo, tag, assets)
        if not can_promote_run(repo, run):
            print("A newer release appeared during upload; leaving the draft unpromoted")
            return
        gh("release", "edit", tag, "--repo", repo, "--draft=false", "--latest")
        finish_release(repo, tag, run, specs, platform, expected=assets)
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
        if (not asset or asset.get("state") != "uploaded"
                or asset["size"] != path.stat().st_size or asset.get("digest") != digest):
            raise RuntimeError(f"Release asset verification failed: {path.name}")


def verify_published(repo, tag, specs, expected=None):
    """Download the published binaries before allowing destructive cleanup."""
    release = find_release(repo, tag)
    if release is None or release["draft"] or release.get("prerelease"):
        raise RuntimeError("A published release is required for cleanup")
    names = {filename for _, _, filename in specs}
    required = names | {"SHA256SUMS.txt"}
    remote = {a["name"]: a for a in release["assets"]}
    if not required <= remote.keys():
        raise RuntimeError("Published release is missing required files")
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        patterns = [arg for name in sorted(required) for arg in ("--pattern", name)]
        gh("release", "download", tag, "--repo", repo, "--dir", directory, *patterns)
        # Validate bytes against GitHub's SHA-256 and the original staged files.
        for name in required:
            path = directory / name
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f"Missing or empty download: {name}")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            asset = remote[name]
            if (asset.get("state") != "uploaded" or asset["size"] != path.stat().st_size
                    or asset.get("digest") != "sha256:" + digest):
                raise RuntimeError(f"Downloaded asset verification failed: {name}")
            if expected is not None and hashlib.sha256((expected / name).read_bytes()).hexdigest() != digest:
                raise RuntimeError(f"Downloaded asset differs from build: {name}")
        manifest = {}
        for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
            digest, name = line.split("  ", 1)
            if name not in names or name in manifest:
                raise RuntimeError("Unexpected checksum manifest entry")
            manifest[name] = digest
        if set(manifest) != names:
            raise RuntimeError("Incomplete checksum manifest")
        for name, digest in manifest.items():
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
                raise RuntimeError(f"Checksum manifest mismatch: {name}")


def cleanup_artifacts(repo, run, allowed_names):
    pages = json.loads(gh("api", f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100",
                          "--paginate", "--slurp"))
    artifacts = [a for page in pages for a in page["artifacts"] if a["name"] in allowed_names]
    # A rerun may reuse the run ID. Never delete a newer attempt's artifacts.
    # Snapshot IDs before checking the attempt, so later artifacts are not included.
    current = api(f"repos/{repo}/actions/runs/{run['id']}")
    if (current["status"] != "completed" or current["conclusion"] != "success"
            or current["run_attempt"] != run["run_attempt"]):
        raise RuntimeError("Build changed during publication; cleanup stopped")
    # Abort before deleting anything if the response refers to a different run.
    if any(a.get("workflow_run", {}).get("id", run["id"]) != run["id"] for a in artifacts):
        raise RuntimeError("Unexpected artifact run ID")
    for artifact in artifacts:
        gh("api", "--method", "DELETE", f"repos/{repo}/actions/artifacts/{artifact['id']}")
        print(f"Deleted Actions artifact: {artifact['name']} ({artifact['id']})")
    print(f"Cleaned {len(artifacts)} artifacts from build {run['id']}")


def finish_release(repo, tag, run, specs, platform, expected=None):
    # ★ 15.10.2026: очистка только после скачивания и проверки опубликованных файлов.
    verify_published(repo, tag, specs, expected)
    if not can_promote_run(repo, run):
        print("A newer release appeared during verification; keeping latest and artifacts unchanged")
        return
    gh("release", "edit", tag, "--repo", repo, "--latest")
    allowed = {artifact for artifact, _, _ in specs}
    if platform == "android":
        allowed.update({"howl-unit-tests", "howl-screenshots"})
    cleanup_artifacts(repo, run, allowed)


if __name__ == "__main__":
    main()
