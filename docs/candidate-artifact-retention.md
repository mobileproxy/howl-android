# Candidate artifact retention

The `Clean candidate artifacts` workflow runs after recognized build/validation
workflows complete, every six hours, and on manual dispatch. It does not build
or publish an application.

- Keep one recent, complete successful candidate bundle per repository, across
  branches. Windows requires Setup and Portable together; Android requires an APK.
- Keep that bundle for at most 24 hours. If no complete successful bundle exists,
  keep only the most recent completed candidate for the same period.
- Remove older known candidate bundles. Separate earlier attempts of a rerun
  using the current attempt's start time before calculating age or selecting a
  bundle. A rerun cannot make a fresh bundle expire with its older artifacts.
- Preserve active builds, artifacts from `main` and `dev`, unknown artifact
  names/workflows, fork runs, workflow logs and all Releases.
- Candidate upload steps use one-day retention as a fallback; uploads on main/dev
  retain seven days for the existing verified-release publisher.

The cleaner checks out only the trusted default branch, never the triggering
branch or artifact contents. Its token has `contents: read` and `actions: write`.
Before each deletion, it checks the run's identity, attempt and completed state
again. API failures stop cleanup rather than authorizing deletion. Only the
Actions artifact DELETE endpoint is used; billing and Releases are not mutated.

Download candidates locally within a day if they are needed longer. Published
Releases remain available independently. The existing publisher still removes
its own main/dev artifacts only after validating the published downloads.

Tests: `python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v`.
Manual preview: dispatch `cleanup-candidates.yml` with `dry_run=true`.
The production default is cleanup enabled. Actions budget and overspend blocking
remain unchanged at the account level.
