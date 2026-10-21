# Recognition stops when idle — 1.0.0-rc21 / build 61

The previous build left a “Waiting to retry unavailable photos” notification after its worker had stopped. It also retained a periodic two-hour discovery job, and refinement retry rows could remain eligible for scheduling after the corresponding face no longer qualified for refinement.

## Changes

- Remove recognition progress notifications when the worker or foreground service finishes its slice. Deferred retry status remains in People; a scheduled retry does not hold a running service, wake lock or progress notification.
- Cancel obsolete batch jobs on completion. If work remains, replace the old batch with one one-shot continuation at the actual remaining-work/retry delay.
- Remove the periodic discovery job, including persisted jobs installed by older versions. Keep Android's image/contact content watcher and boot/app-open reconciliation so new photos still process automatically. A content watcher registration does not run inference or hold the app awake while idle.
- Keep the existing three-attempt limit for individual unavailable photos/portraits. Add a three-attempt limit for repeated background run-level and contact-provider failures. Exhausted contact-provider attempts wait for a contact change, explicit retry/invalidation or contact recognition being enabled again. Run-level failures can be retried on an app reconciliation or new content event.
- Only schedule refinement retries for faces still in the current refinement plan. Manual, excluded or otherwise ineligible faces cannot leave a stale retry wakeup behind.
- Preserve one follow-up check when an image/contact event arrives during an active background slice; finishing the old slice must not lose the event.
- Preserve saved results, zero-face completion, names and corrections, automatic processing of new photos and the explicit 24-hour pause/resume job. No database migration is needed.

## Device acceptance

Build and sign with the existing GitHub Actions release workflow, then install over the current build. A completed scan should remove its notification. An unavailable file should stop the current run and retry later without an idle notification; after three failures it should no longer schedule automatic retries for that unchanged file. Add a new face-free image and confirm one detection result is saved without a matching phase, then add a face image and confirm processing resumes. Check that Pause still schedules its 24-hour resume.

Android controls delivery times for scheduled jobs. Unit tests and release builds cannot establish actual battery use or notification timing on a physical phone.

## Validation

All 557 Android tests passed, including eight new regression tests; no failures, errors or skips. Release lint passed with zero errors and 161 warnings (including existing style/deprecation guidance). Release APK/AAB build and model, manifest, archive and native alignment checks passed. Four release-tool tests, static publishing checks and `git diff --check` passed. Detailed results: [validation/recognition-idle-rc21.json](validation/recognition-idle-rc21.json). Validation packages are unsigned; the existing signed release workflow is retained.
