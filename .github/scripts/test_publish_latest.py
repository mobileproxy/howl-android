import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_latest as publisher


class ReleaseCleanupTests(unittest.TestCase):
    def setUp(self):
        self.repo = "owner/repo"
        self.run = {"id": 42, "run_attempt": 1}
        self.specs = [("howl-apk", "*.apk", "Howl.apk")]

    def test_cleanup_only_after_verification_and_promotion(self):
        events = []
        with patch.object(publisher, "verify_published", side_effect=lambda *a: events.append("verified")), \
             patch.object(publisher, "can_promote_run", return_value=True), \
             patch.object(publisher, "gh", side_effect=lambda *a: events.append("promoted")), \
             patch.object(publisher, "cleanup_artifacts", side_effect=lambda *a: events.append(("cleaned", a[2]))):
            publisher.finish_release(self.repo, "ci-42-1", self.run, self.specs, "android")
        self.assertEqual(events[:2], ["verified", "promoted"])
        self.assertEqual(events[2], ("cleaned", {"howl-apk", "howl-unit-tests", "howl-screenshots"}))

    def test_verification_failure_never_deletes(self):
        with patch.object(publisher, "verify_published", side_effect=RuntimeError("checksum")), \
             patch.object(publisher, "gh") as gh, patch.object(publisher, "cleanup_artifacts") as cleanup:
            with self.assertRaises(RuntimeError):
                publisher.finish_release(self.repo, "ci-42-1", self.run, self.specs, "android")
        gh.assert_not_called()
        cleanup.assert_not_called()

    def test_promotion_failure_never_deletes(self):
        with patch.object(publisher, "verify_published"), \
             patch.object(publisher, "can_promote_run", return_value=True), \
             patch.object(publisher, "gh", side_effect=RuntimeError("API")), \
             patch.object(publisher, "cleanup_artifacts") as cleanup:
            with self.assertRaises(RuntimeError):
                publisher.finish_release(self.repo, "ci-42-1", self.run, self.specs, "android")
        cleanup.assert_not_called()

    def test_cleanup_scoped_to_run_and_known_names_with_pagination(self):
        pages = [{"artifacts": [{"id": 1, "name": "howl-apk", "workflow_run": {"id": 42}},
                                {"id": 2, "name": "unknown"}]},
                 {"artifacts": [{"id": 3, "name": "howl-unit-tests"}]}]
        current = {"status": "completed", "conclusion": "success", "run_attempt": 1}
        with patch.object(publisher, "api", return_value=current), \
             patch.object(publisher, "gh", side_effect=[json.dumps(pages), "", ""]) as gh:
            publisher.cleanup_artifacts(self.repo, self.run, {"howl-apk", "howl-unit-tests"})
        self.assertIn("/runs/42/artifacts?", gh.call_args_list[0].args[1])
        self.assertEqual([call.args[-1] for call in gh.call_args_list[1:]],
                         ["repos/owner/repo/actions/artifacts/1", "repos/owner/repo/actions/artifacts/3"])

    def test_new_attempt_and_wrong_run_block_cleanup(self):
        for attempt, artifact_run in [(2, 42), (1, 99)]:
            pages = [{"artifacts": [{"id": 1, "name": "howl-apk", "workflow_run": {"id": artifact_run}}]}]
            with patch.object(publisher, "api", return_value={
                    "status": "completed", "conclusion": "success", "run_attempt": attempt}), \
                 patch.object(publisher, "gh", return_value=json.dumps(pages)) as gh:
                with self.assertRaises(RuntimeError):
                    publisher.cleanup_artifacts(self.repo, self.run, {"howl-apk"})
            self.assertFalse(any("DELETE" in call.args for call in gh.call_args_list))

    def test_retry_after_cleanup_is_noop(self):
        with patch.object(publisher, "api", return_value={
                "status": "completed", "conclusion": "success", "run_attempt": 1}), \
             patch.object(publisher, "gh", return_value='[{"artifacts": []}]') as gh:
            publisher.cleanup_artifacts(self.repo, self.run, {"howl-apk"})
        self.assertEqual(gh.call_count, 1)

    def test_published_download_integrity(self):
        binary = b"example apk"
        checksum = hashlib.sha256(binary).hexdigest()
        files = {"Howl.apk": binary, "SHA256SUMS.txt": f"{checksum}  Howl.apk\n".encode()}
        release = {"draft": False, "assets": [
            {"name": name, "size": len(data), "state": "uploaded",
             "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
            for name, data in files.items()
        ]}
        real_temp = tempfile.TemporaryDirectory
        def local_temp():
            return real_temp(dir=Path.cwd())
        def download(*args):
            directory = Path(args[args.index("--dir") + 1])
            for name, data in files.items():
                (directory / name).write_bytes(data)
        with patch.object(publisher, "find_release", return_value=release), \
             patch.object(publisher, "gh", side_effect=download), \
             patch.object(publisher.tempfile, "TemporaryDirectory", side_effect=local_temp):
            publisher.verify_published(self.repo, "ci-42-1", self.specs)
            files["Howl.apk"] = b"corrupted"
            with self.assertRaises(RuntimeError):
                publisher.verify_published(self.repo, "ci-42-1", self.specs)

    def test_draft_and_missing_assets_block_download(self):
        for release in [{"draft": True}, {"draft": False, "assets": []}, None]:
            with patch.object(publisher, "find_release", return_value=release), \
                 patch.object(publisher, "gh") as gh:
                with self.assertRaises(RuntimeError):
                    publisher.verify_published(self.repo, "ci-42-1", self.specs)
            gh.assert_not_called()

    def test_old_or_divergent_build_cannot_replace_manual_release(self):
        pages = [[{"draft": False, "prerelease": False, "tag_name": "v1.0.1"}]]
        for status in ("behind", "diverged"):
            with patch.object(publisher, "gh", return_value=json.dumps(pages)), \
                 patch.object(publisher, "api", return_value={"status": status}):
                self.assertFalse(publisher.can_promote_run(self.repo, {"head_sha": "older"}))

    def test_same_or_newer_source_can_be_published(self):
        pages = [[{"draft": False, "tag_name": "v1.0.1"}, {"draft": True},
                  {"draft": False, "prerelease": True}]]
        for status in ("ahead", "identical"):
            with patch.object(publisher, "gh", return_value=json.dumps(pages)), \
                 patch.object(publisher, "api", return_value={"status": status}) as api:
                self.assertTrue(publisher.can_promote_run(self.repo, {"head_sha": "newer"}))
                self.assertEqual(api.call_count, 1)

    def test_first_publication_is_allowed(self):
        with patch.object(publisher, "gh", return_value="[[]]"), patch.object(publisher, "api") as api:
            self.assertTrue(publisher.can_promote_run(self.repo, {"head_sha": "first"}))
        api.assert_not_called()

    def test_newer_release_during_download_blocks_promotion_and_cleanup(self):
        with patch.object(publisher, "verify_published"), \
             patch.object(publisher, "can_promote_run", return_value=False), \
             patch.object(publisher, "gh") as gh, \
             patch.object(publisher, "cleanup_artifacts") as cleanup:
            publisher.finish_release(self.repo, "ci-42-1", self.run, self.specs, "android")
        gh.assert_not_called()
        cleanup.assert_not_called()

    def test_comparison_failure_blocks_promotion(self):
        pages = [[{"draft": False, "tag_name": "v1.0.1"}]]
        with patch.object(publisher, "gh", return_value=json.dumps(pages)), \
             patch.object(publisher, "api", side_effect=RuntimeError("API unavailable")):
            with self.assertRaises(RuntimeError):
                publisher.can_promote_run(self.repo, {"head_sha": "candidate"})


if __name__ == "__main__":
    unittest.main()
