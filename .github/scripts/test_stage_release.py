import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_latest as publisher


class DraftReleaseTests(unittest.TestCase):
    repo = "owner/repo"
    build = {"id": 42, "run_number": 7, "run_attempt": 2, "head_sha": "a" * 40,
           "html_url": "https://github.com/owner/repo/actions/runs/42",
           "head_branch": "dev", "event": "push", "status": "completed",
           "conclusion": "success", "head_repository": {"full_name": repo},
           "path": ".github/workflows/build.yml"}
    specs = [("howl-apk", "*universal*.apk", "Howl.apk")]

    def release(self, files=None):
        files = files or {"Howl.apk": b"verified apk"}
        return {"draft": True, "prerelease": False, "tag_name": "ci-42-2",
                "target_commitish": self.build["head_sha"], "assets": [
                    {"name": name, "size": len(data), "state": "uploaded",
                     "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
                    for name, data in files.items()]}

    def test_identity_rejects_published_wrong_source_tag_and_prerelease(self):
        for changes in ({"draft": False}, {"target_commitish": "b" * 40},
                        {"tag_name": "ci-41-1"}, {"prerelease": True}):
            release = {**self.release(), **changes}
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                publisher.verify_draft_identity(release, self.build)
        publisher.verify_draft_identity(self.release(), self.build)

    def test_missing_draft_blocks_download(self):
        with patch.object(publisher, "find_release", return_value=None), \
             patch.object(publisher, "gh") as gh, tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RuntimeError):
                publisher.download_draft(self.repo, self.build, self.specs, Path(directory))
        gh.assert_not_called()

    def test_draft_download_checks_real_bytes_github_digest_and_manifest(self):
        binary = b"verified apk"
        digest = hashlib.sha256(binary).hexdigest()
        files = {"Howl.apk": binary, "SHA256SUMS.txt": f"{digest}  Howl.apk\n".encode()}
        release = self.release(files)
        def download(*args):
            directory = Path(args[args.index("--dir") + 1])
            for name, data in files.items():
                (directory / name).write_bytes(data)
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(publisher, "find_release", return_value=release), \
             patch.object(publisher, "gh", side_effect=download):
            publisher.download_draft(self.repo, self.build, self.specs, Path(temp))
            files["Howl.apk"] = b"modified bytes"
            with self.assertRaises(RuntimeError):
                publisher.download_draft(self.repo, self.build, self.specs, Path(temp))

    def test_successful_run_and_current_branch_are_rechecked(self):
        for change in ({"run_attempt": 3}, {"conclusion": "failure"},
                       {"head_sha": "b" * 40}, {"event": "pull_request"},
                       {"head_repository": {"full_name": "other/repo"}},
                       {"path": ".github/workflows/other.yml"}):
            with self.subTest(change=change), \
                 patch.object(publisher, "api", side_effect=[{**self.build, **change}, {"commit": {"sha": self.build["head_sha"]}}]), \
                 self.assertRaises(RuntimeError):
                publisher.verify_current_build(self.repo, self.build, "dev")
        with patch.object(publisher, "api", side_effect=[self.build, {"commit": {"sha": "b" * 40}}]), \
             self.assertRaises(RuntimeError):
            publisher.verify_current_build(self.repo, self.build, "dev")
        with patch.object(publisher, "api", side_effect=[self.build, {"commit": {"sha": self.build["head_sha"]}}]):
            publisher.verify_current_build(self.repo, self.build, "dev")

    def test_stage_never_overwrites_published_or_wrong_identity(self):
        import stage_release
        for changes in ({"draft": False}, {"target_commitish": "b" * 40}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as temp, \
                 patch.object(publisher, "find_release", return_value={**self.release(), **changes}), \
                 patch.object(publisher, "gh") as gh, self.assertRaises(RuntimeError):
                stage_release.upload_draft(self.repo, self.build, Path(temp), "android")
            gh.assert_not_called()

    def test_stage_uploads_only_draft_then_checks_remote_download(self):
        import stage_release
        events = []
        def download(repo, run, specs, destination):
            events.append(("download",))
            for path in Path(temp).iterdir():
                (destination / path.name).write_bytes(path.read_bytes())
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(publisher, "find_release", side_effect=[None, self.release()]), \
             patch.object(publisher, "gh", side_effect=lambda *args: events.append(args)), \
             patch.object(publisher, "verify_assets", side_effect=lambda *args: events.append(("metadata",))), \
             patch.object(publisher, "download_draft", side_effect=download):
            (Path(temp) / "Howl.apk").write_bytes(b"verified apk")
            stage_release.upload_draft(self.repo, self.build, Path(temp), "android")
        self.assertIn("--draft", events[0])
        self.assertIn(self.build["head_sha"], events[0])
        self.assertEqual(events[1][:2], ("release", "upload"))
        self.assertEqual(events[2:], [("metadata",), ("download",)])
        self.assertFalse(any("--latest" in event or "edit" in event for event in events))

    def test_manifest_tampering_and_missing_assets_fail_with_valid_github_digests(self):
        binary = b"verified apk"
        files = {"Howl.apk": binary, "SHA256SUMS.txt": f"{'0' * 64}  Howl.apk\n".encode()}
        def download(*args):
            directory = Path(args[args.index("--dir") + 1])
            for name, data in files.items():
                (directory / name).write_bytes(data)
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(publisher, "find_release", return_value=self.release(files)), \
             patch.object(publisher, "gh", side_effect=download), self.assertRaises(RuntimeError):
            publisher.download_draft(self.repo, self.build, self.specs, Path(temp))
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(publisher, "find_release", return_value=self.release()), \
             patch.object(publisher, "gh") as gh, self.assertRaises(RuntimeError):
            publisher.download_draft(self.repo, self.build, self.specs, Path(temp))
        gh.assert_not_called()

    def test_new_branch_head_after_draft_download_blocks_promotion(self):
        env = {"GITHUB_REPOSITORY": self.repo, "BUILD_BRANCH": "dev", "BUILD_PLATFORM": "android",
               "BUILD_TRANSPORT": "release-draft"}
        def download(repo, run, specs, destination):
            (destination / "Howl.apk").write_bytes(b"verified apk")
            digest = hashlib.sha256(b"verified apk").hexdigest()
            (destination / "SHA256SUMS.txt").write_text(f"{digest}  Howl.apk\n")
        with patch.dict(os.environ, env), \
             patch.object(publisher, "api", return_value={"workflow_runs": [self.build]}), \
             patch.object(publisher, "can_promote_run", return_value=True), \
             patch.object(publisher, "find_release", return_value=self.release()), \
             patch.object(publisher, "download_draft", side_effect=download), \
             patch.object(publisher, "verify_assets"), \
             patch.object(publisher, "verify_current_build", side_effect=[None, RuntimeError("branch moved")]), \
             patch.object(publisher, "gh") as gh, self.assertRaises(RuntimeError):
            publisher.main()
        gh.assert_not_called()

    def test_stage_main_rejects_untrusted_context_before_upload(self):
        import stage_release
        trusted = {**self.build, "status": "in_progress"}
        env = {"GITHUB_REPOSITORY": self.repo, "GITHUB_REF": "refs/heads/dev", "GITHUB_RUN_ID": "42",
               "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": self.build["head_sha"], "BUILD_PLATFORM": "android",
               "BUILD_OUTPUT": "."}
        for changes in ({"GITHUB_REF": "refs/heads/fork"}, {"GITHUB_SHA": "b" * 40},
                        {"GITHUB_RUN_ATTEMPT": "3"}):
            with self.subTest(changes=changes), patch.dict(os.environ, {**env, **changes}), \
                 patch.object(publisher, "api", return_value=trusted), \
                 patch.object(stage_release, "upload_draft") as upload, self.assertRaises(RuntimeError):
                stage_release.main()
            upload.assert_not_called()

    def test_windows_main_stages_only_distribution_files_despite_nested_executables(self):
        import stage_release
        trusted = {**self.build, "status": "in_progress", "head_branch": "main"}
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "Howl-Setup-1.0.39.exe").write_bytes(b"verified setup")
            (source / "Howl-Portable-1.0.39.zip").write_bytes(b"verified portable")
            (source / "app").mkdir()
            (source / "app/Howl.exe").write_bytes(b"decoy gui")
            (source / "app/HowlService.exe").write_bytes(b"decoy service")
            (source / "app/core.zip").write_bytes(b"decoy zip")
            env = {"GITHUB_REPOSITORY": self.repo, "GITHUB_REF": "refs/heads/main", "GITHUB_RUN_ID": "42",
                   "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": self.build["head_sha"], "BUILD_PLATFORM": "windows",
                   "BUILD_OUTPUT": str(source)}
            staged = {}
            def upload(repo, run, assets, platform):
                staged.update({path.name: path.read_bytes() for path in assets.iterdir()})
                self.assertEqual(platform, "windows")
            with patch.dict(os.environ, env), patch.object(publisher, "api", return_value=trusted), \
                 patch.object(stage_release, "upload_draft", side_effect=upload):
                stage_release.main()
            self.assertEqual(staged, {"Howl-Setup.exe": b"verified setup", "Howl-Portable.zip": b"verified portable"})
            with patch.dict(os.environ, {**env, "GITHUB_REF": "refs/heads/dev"}), \
                 patch.object(publisher, "api", return_value={**trusted, "head_branch": "dev"}), \
                 patch.object(stage_release, "upload_draft") as upload_mock, self.assertRaises(RuntimeError):
                stage_release.main()
            upload_mock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
