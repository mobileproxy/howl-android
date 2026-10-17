import json
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import cleanup_candidates as cleanup


NOW = datetime(2026, 10, 17, 12, tzinfo=timezone.utc)
REPO = 'mobileproxy/howl-windows'


def artifact(i, run=1, name='howl-setup', branch='candidate/a', hours=1):
    return {'id': i, 'name': name, 'expired': False, 'size_in_bytes': 100,
            'created_at': (NOW - timedelta(hours=hours)).isoformat(),
            'workflow_run': {'id': run, 'head_branch': branch}}


def run(i, branch='candidate/a', conclusion='success', status='completed', path='.github/workflows/build.yml'):
    return {'id': i, 'head_branch': branch, 'head_repository': {'full_name': REPO},
            'status': status, 'conclusion': conclusion, 'path': path,
            'head_sha': str(i), 'run_attempt': 1, 'event': 'workflow_dispatch'}


class CandidatePolicyTests(unittest.TestCase):
    def planned(self, artifacts, runs, platform='windows'):
        return cleanup.plan_cleanup(artifacts, runs, REPO, platform, NOW)

    def ids(self, plan):
        return [a['id'] for a in plan]

    def test_keep_newest_complete_candidate_across_branches(self):
        artifacts = [artifact(1), artifact(2, name='howl-portable'),
                     artifact(3, run=2, branch='candidate/b', hours=0),
                     artifact(4, run=2, name='howl-portable', branch='candidate/b', hours=0)]
        self.assertEqual(self.ids(self.planned(artifacts, {1: run(1), 2: run(2, 'candidate/b')})), [1, 2])

    def test_newer_failed_run_does_not_displace_successful_bundle(self):
        artifacts = [artifact(1), artifact(2, name='howl-portable'), artifact(3, run=2, hours=0)]
        self.assertEqual(self.ids(self.planned(artifacts, {1: run(1), 2: run(2, conclusion='failure')})), [3])

    def test_latest_candidate_also_expires_after_one_day(self):
        artifacts = [artifact(1, hours=25), artifact(2, name='howl-portable', hours=25)]
        self.assertEqual(self.ids(self.planned(artifacts, {1: run(1)})), [1, 2])

    def test_exact_age_boundary_is_expired(self):
        self.assertEqual(self.ids(self.planned([artifact(1, hours=24)], {1: run(1)})), [1])

    def test_main_dev_and_release_branch_are_always_protected(self):
        for branch in ('main', 'dev'):
            with self.subTest(branch=branch):
                a = artifact(1, branch=branch, hours=80)
                self.assertEqual(self.planned([a], {1: run(1, branch)}), [])

    def test_unknown_artifact_workflow_fork_and_event_are_protected(self):
        cases = [([artifact(1, name='unrelated', hours=80)], run(1)),
                 ([artifact(1, hours=80)], run(1, path='.github/workflows/unrelated.yml')),
                 ([artifact(1, hours=80)], run(1) | {'head_repository': {'full_name': 'fork/repo'}}),
                 ([artifact(1, hours=80)], run(1) | {'head_repository': None}),
                 ([artifact(1, hours=80)], run(1) | {'event': 'schedule'})]
        for artifacts, value in cases:
            with self.subTest(run=value):
                self.assertEqual(self.planned(artifacts, {1: value}), [])

    def test_active_run_is_never_deleted_even_when_old(self):
        self.assertEqual(self.planned([artifact(1, hours=80)], {1: run(1, status='in_progress')}), [])

    def test_branch_mismatch_is_never_deleted(self):
        self.assertEqual(self.planned([artifact(1, hours=80)], {1: run(1, 'main')}), [])

    def test_newer_incomplete_run_does_not_displace_complete_bundle(self):
        artifacts = [artifact(1), artifact(2, name='howl-portable'), artifact(3, run=2, hours=0)]
        self.assertEqual(self.ids(self.planned(artifacts, {1: run(1), 2: run(2)})), [3])

    def test_one_recent_failure_kept_if_no_successful_bundle_exists(self):
        artifacts = [artifact(1, run=1, hours=2), artifact(2, run=2, hours=1)]
        runs = {i: run(i, conclusion='failure') for i in (1, 2)}
        self.assertEqual(self.ids(self.planned(artifacts, runs)), [1])

    def test_partial_expiry_removes_the_entire_completed_bundle(self):
        artifacts = [artifact(1, hours=25), artifact(2, name='howl-portable', hours=23)]
        self.assertEqual(self.ids(self.planned(artifacts, {1: run(1)})), [1, 2])

    def test_rerun_keeps_new_bundle_even_when_previous_attempt_is_expired(self):
        artifacts = [artifact(1, hours=25), artifact(2, name='howl-portable', hours=25),
                     artifact(3, hours=1), artifact(4, name='howl-portable', hours=1)]
        current = run(1) | {'run_attempt': 2, 'run_started_at': (NOW - timedelta(hours=2)).isoformat()}
        self.assertEqual(self.ids(self.planned(artifacts, {1: current})), [1, 2])

    def test_rerun_does_not_keep_multiple_recent_attempts(self):
        artifacts = [artifact(1, hours=3), artifact(2, name='howl-portable', hours=3),
                     artifact(3, hours=1), artifact(4, name='howl-portable', hours=1)]
        current = run(1) | {'run_attempt': 2, 'run_started_at': (NOW - timedelta(hours=2)).isoformat()}
        self.assertEqual(self.ids(self.planned(artifacts, {1: current})), [1, 2])

    def test_rerun_without_attempt_start_fails_closed(self):
        with self.assertRaises(ValueError):
            self.planned([artifact(1, hours=25)], {1: run(1) | {'run_attempt': 2}})

    def test_loader_uses_latest_attempt_timestamp(self):
        current = run(1) | {'run_attempt': 2, 'run_started_at': 'old'}
        attempt = current | {'run_started_at': (NOW - timedelta(hours=2)).isoformat()}
        with patch.object(cleanup, 'gh', side_effect=[json.dumps(current), json.dumps(attempt)]) as gh:
            result = cleanup.load_run(REPO, 1)
            self.assertEqual(result['run_started_at'], attempt['run_started_at'])
            self.assertTrue(gh.call_args.args[1].endswith('/runs/1/attempts/2'))

    def test_loader_refuses_inconsistent_attempt_metadata(self):
        current = run(1) | {'run_attempt': 2}
        with patch.object(cleanup, 'gh', side_effect=[json.dumps(current), json.dumps(run(1))]):
            with self.assertRaises(ValueError):
                cleanup.load_run(REPO, 1)

    def test_loader_refuses_conflicting_attempt_status(self):
        current = run(1) | {'run_attempt': 2}
        attempt = current | {'status': 'in_progress', 'conclusion': None,
                             'run_started_at': (NOW - timedelta(hours=1)).isoformat()}
        with patch.object(cleanup, 'gh', side_effect=[json.dumps(current), json.dumps(attempt)]):
            with self.assertRaises(ValueError):
                cleanup.load_run(REPO, 1)

    def test_android_understands_both_candidate_apk_names(self):
        artifacts = [artifact(1, name='howl-universal-candidate', hours=2),
                     artifact(2, run=2, name='howl-apk', hours=1),
                     artifact(3, run=2, name='howl-unit-tests', hours=1)]
        runs = {i: run(i) for i in (1, 2)}
        self.assertEqual(self.ids(self.planned(artifacts, runs, 'android')), [1])

    def test_expired_and_empty_inputs_are_noop(self):
        self.assertEqual(self.planned([], {}), [])
        self.assertEqual(self.planned([artifact(1, hours=80) | {'expired': True}], {}), [])

    def test_missing_metadata_fails_closed(self):
        with self.assertRaises(ValueError):
            self.planned([artifact(1, hours=80)], {})

    def test_rerun_started_after_plan_skips_delete(self):
        a = artifact(1, hours=80)
        before = {1: run(1)}
        deleted = []
        for changed in (run(1, status='in_progress'), run(1) | {'run_attempt': 2}, run(1, 'main')):
            cleanup.execute_cleanup([a], before, lambda i: changed, deleted.append, False)
        self.assertEqual(deleted, [])

    def test_dry_run_never_deletes(self):
        a = artifact(1, hours=80)
        deleted = []
        cleanup.execute_cleanup([a], {1: run(1)}, lambda i: run(i), deleted.append, True)
        self.assertEqual(deleted, [])

    def test_delete_is_scoped_to_artifact_ids_and_reports_actual_count(self):
        a = artifact(1, hours=80)
        deleted = []
        result = cleanup.execute_cleanup([a], {1: run(1)}, lambda i: run(i), deleted.append, False)
        self.assertEqual(deleted, [1])
        self.assertEqual(result, 1)

    def test_metadata_api_error_prevents_delete(self):
        deleted = []
        def fail(_):
            raise RuntimeError('API unavailable')
        with self.assertRaises(RuntimeError):
            cleanup.execute_cleanup([artifact(1)], {1: run(1)}, fail, deleted.append, False)
        self.assertEqual(deleted, [])

    def test_pagination_collects_all_artifacts(self):
        pages = [{'artifacts': [artifact(1)]}, {'artifacts': [artifact(2)]}]
        with patch.object(cleanup, 'gh', return_value=json.dumps(pages)) as gh:
            self.assertEqual([a['id'] for a in cleanup.list_artifacts(REPO)], [1, 2])
            self.assertIn('--paginate', gh.call_args.args)
            self.assertIn('--slurp', gh.call_args.args)


if __name__ == '__main__':
    unittest.main()
