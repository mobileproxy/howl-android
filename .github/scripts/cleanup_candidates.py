"""Keep one recent candidate bundle; never delete protected-branch/release data."""
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import subprocess


PROTECTED_BRANCHES = {'main', 'dev'}
MAX_AGE = timedelta(hours=24)
POLICIES = {
    'windows': {
        'names': {'howl-setup', 'howl-portable'},
        'paths': {'.github/workflows/build.yml'},
        'bundles': ({'howl-setup', 'howl-portable'},),
    },
    'android': {
        'names': {'howl-apk', 'howl-unit-tests', 'howl-screenshots',
                  'howl-universal-candidate', 'universal-unit-tests', 'universal-screenshots'},
        'paths': {'.github/workflows/build.yml', '.github/workflows/validate-universal-arm.yml',
                  '.github/workflows/validate-android-candidate.yml'},
        'bundles': ({'howl-apk'}, {'howl-universal-candidate'}),
    },
}


def gh(*args):
    return subprocess.check_output(['gh', *map(str, args)], text=True).strip()


def list_artifacts(repo):
    pages = json.loads(gh('api', f'repos/{repo}/actions/artifacts?per_page=100', '--paginate', '--slurp'))
    artifacts = [a for page in pages for a in page['artifacts']]
    ids = [a['id'] for a in artifacts]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate artifact IDs; refusing cleanup')
    return artifacts


def load_run(repo, run_id):
    run = json.loads(gh('api', f'repos/{repo}/actions/runs/{int(run_id)}'))
    if run.get('run_attempt', 1) > 1:
        attempt = json.loads(gh('api', f'repos/{repo}/actions/runs/{int(run_id)}/attempts/{int(run["run_attempt"])}'))
        # The general run timestamp may describe the original execution. Use
        # the current attempt endpoint, never infer attempt from artifact name.
        if (identity(attempt) != identity(run) or attempt.get('status') != run.get('status')
                or attempt.get('conclusion') != run.get('conclusion')):
            raise ValueError('Workflow attempt changed; refusing cleanup')
        timestamp(attempt['run_started_at'])
        run['run_started_at'] = attempt['run_started_at']
    return run


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Timezone required')
    return result


def candidate_metadata(run, repo, policy):
    return (run.get('status') == 'completed'
            and run.get('head_branch') not in PROTECTED_BRANCHES
            and bool(run.get('head_branch'))
            and (run.get('head_repository') or {}).get('full_name') == repo
            and run.get('path', '').split('@', 1)[0] in policy['paths']
            and run.get('event') in {'push', 'workflow_dispatch', 'pull_request'})


def plan_cleanup(artifacts, runs, repo, platform, now):
    policy = POLICIES[platform]
    groups = {}
    previous_attempts = []
    for artifact in artifacts:
        meta = artifact.get('workflow_run', {})
        if (artifact.get('expired') or artifact['name'] not in policy['names']
                or meta.get('head_branch') in PROTECTED_BRANCHES):
            continue
        run = runs.get(meta.get('id'))
        if run is None:
            raise ValueError('Missing run metadata; refusing cleanup')
        if (run.get('id') != meta.get('id') or not candidate_metadata(run, repo, policy)
                or run.get('head_branch') != meta.get('head_branch')):
            continue
        if not isinstance(artifact['id'], int) or artifact['id'] <= 0:
            raise ValueError('Invalid artifact ID')
        if run.get('run_attempt', 1) > 1:
            if not run.get('run_started_at'):
                raise ValueError('Current attempt start required')
            if timestamp(artifact['created_at']) < timestamp(run['run_started_at']):
                previous_attempts.append(artifact)
                continue
        groups.setdefault(run['id'], []).append(artifact)

    # A bundle expires together. Do not keep half of an old setup/portable pair.
    cutoff = now - MAX_AGE
    fresh = {i: values for i, values in groups.items()
             if min(timestamp(a['created_at']) for a in values) > cutoff}
    successful = [i for i, values in fresh.items() if runs[i].get('conclusion') == 'success'
                  and any(bundle <= {a['name'] for a in values} for bundle in policy['bundles'])]
    choices = successful or list(fresh)
    keep = max(choices, key=lambda i: (max(timestamp(a['created_at']) for a in fresh[i]), i)) if choices else None
    return previous_attempts + [a for i, values in groups.items() if i != keep for a in values]


def identity(run):
    return tuple(run.get(k) for k in ('id', 'head_branch', 'head_sha', 'run_attempt', 'path', 'event')) + (
        (run.get('head_repository') or {}).get('full_name'),)


def execute_cleanup(plan, runs, get_run, delete_artifact, dry_run):
    deleted = 0
    for artifact in plan:
        run_id = artifact['workflow_run']['id']
        current = get_run(run_id)
        if current.get('status') != 'completed' or identity(current) != identity(runs[run_id]):
            print(f'Skip artifact {artifact["id"]}: run changed or restarted')
            continue
        if not dry_run:
            delete_artifact(artifact['id'])
            deleted += 1
    return deleted


def main():
    repo = os.environ['GITHUB_REPOSITORY']
    platform = os.environ['CANDIDATE_PLATFORM']
    if repo != f'mobileproxy/howl-{platform}' or platform not in POLICIES:
        raise ValueError('Unexpected repository/platform')
    policy = POLICIES[platform]
    dry_run = os.environ.get('DRY_RUN', 'false').lower()
    if dry_run not in {'true', 'false'}:
        raise ValueError('Invalid dry-run setting')
    artifacts = list_artifacts(repo)
    candidates = [a for a in artifacts if a['name'] in policy['names'] and not a.get('expired')
                  and a.get('workflow_run', {}).get('head_branch') not in PROTECTED_BRANCHES]

    def get_run(i):
        return load_run(repo, i)

    # Resolve all metadata before issuing the first destructive request.
    runs = {i: get_run(i) for i in {a['workflow_run']['id'] for a in candidates}}
    plan = plan_cleanup(artifacts, runs, repo, platform, datetime.now(timezone.utc))

    def delete_artifact(i):
        # This is the only mutation. No release, workflow-run, cache or billing API.
        gh('api', '--method', 'DELETE', f'repos/{repo}/actions/artifacts/{int(i)}')

    deleted = execute_cleanup(plan, runs, get_run, delete_artifact, dry_run == 'true')
    result = {'knownArtifacts': len(candidates), 'plannedDeletions': len(plan),
              'plannedBytes': sum(a['size_in_bytes'] for a in plan),
              'deletedArtifacts': deleted, 'dryRun': dry_run == 'true'}
    print(json.dumps(result))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a', encoding='utf-8') as output:
            output.write('## Candidate artifact cleanup\n\n```json\n' + json.dumps(result, indent=2) + '\n```\n')


if __name__ == '__main__':
    main()
