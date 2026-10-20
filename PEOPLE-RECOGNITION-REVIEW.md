# Face Gallery rc17 / build 57

Baseline: rc16 / build 56 in this workspace. App ID remains com.mosaic.gallery. Use an update signed with the existing key and keep app data.

## People loading

The launch cache previously used a combined photo/video permission scope. On a phone with full photo access, selected-media permission and no full video access, that could reject all saved People previews. People display data and private face covers now use the photo permission they actually need. Changing video access does not discard them. Selected-only photo access still requires a current accessible-media check.

Startup restores the small group list and existing cover JPEGs immediately on independent workers. It also restores/prepares the complete People data from saved photo metadata before the People screen is opened. Fresh gallery metadata starts a preload as soon as it is available. Contact provider notifications mark the data for refresh instead of deleting the launch display. The previous compact snapshot format and existing face cover previews are read during the upgrade where their original permission scope remains valid.

Foreground recognition no longer holds one writer lock over the entire photo library. It yields between photos and between bounded grouping passes. Groups can appear during the run, and People readers can obtain a current snapshot without waiting for the whole run to finish. Existing recognition models, saved identities, correction precedence and similarity thresholds are preserved.

These changes remove identified startup delays; physical-device latency has not been timed in this environment. A first-ever scan still needs to find faces before it can produce groups.

## Album folders

A folder starts transparent and begins its transition only after its first layout. The existing album grid stays underneath. A short fade and small translation replace the scaling/clipping of a newly created, partly empty grid. The folder has an opaque themed background; bounded cover thumbnails are handed between adapters. Closing content stays transparent until it is removed, avoiding the previous final-frame flash. Tag folders follow the same transition path.

## Recognition controls and background work

People has a compact Start / Pause for 24 hours / Resume control, a progress line and a progress bar. Permission/consent alone no longer starts a new recognition session. Press Start to begin; existing results are retained. After starting, persisted jobs monitor new photos and recover unfinished work. Explicit recognition tools and the privacy screen also start or pause the same workflow.

A user-started run recognises photos, updates logical People folders during the run, refines difficult matches and checks optional contact portraits. Original device photos are not moved into physical folders. Work commits incrementally. Pause saves a 24-hour deadline, cancels normal jobs and schedules one persisted resume job. Reboot restores the remaining pause instead of starting another 24 hours. Resume now clears the timer. An existing timed pause from the previous release is migrated as previously started work.

All app battery percentage, charging, battery-saver and temperature/thermal gates were removed from recognition and contact tools. Existing scheduler jobs with battery constraints are replaced. Permission checks, cancellation, storage availability and Android's own execution limits remain. Automatic continuation is scheduled for the pause deadline; Android can delay job execution, and force-stop prevents it until the app is reopened.

## Notifications and estimates

Active work has a silent progress notification with the actual current step and counts. Where a step has known work, the progress bar uses those counts. Estimates use work completed during the current run, reset for a new step, and are labelled as estimates for that step. Preparing/comparing steps with unknown totals use an indeterminate bar rather than an invented percentage or ETA. Paused notifications show the resume date/time and a Resume now action. Completed work is shown separately from scheduled continuation. Notification visibility remains subject to the user's Android notification permission.

## Validation

All 526 Android unit/regression tests passed, with no failures or skipped tests. Release lint completed with zero errors and 160 warnings. Release APK and AAB builds passed. The four release-tool tests, static publishing checks, archive integrity, recognition-model hash, manifest rules, APK alignment and native 16 KB compatibility checks passed. Exact results and artifact hashes are recorded in validation/people-recognition-rc17.json. Local validation APK/AAB files are unsigned; build installable releases with the existing GitHub Actions signing secrets. This source package has not been pushed to GitHub.

## Phone acceptance

- Open the app, browse Photos briefly, then open People. Check cold start, tab return, and a phone granting all photos but only selected/no videos. Revoke photo access and confirm old unrestricted previews are rejected.
- Open and close Camera, All, Favorites and tag folders repeatedly. Watch the first and last animation frames while thumbnails load, and repeat during a media refresh.
- Start in People, leave/reopen the screen, lock/unlock the phone, and verify counts and folders progress. Check a cold/hot/low-battery/battery-saver device state: the app must not apply its old gates.
- Pause in People and from the notification. Check saved time after process restart and reboot, Resume now, and automatic continuation after the deadline. Check notification cancellation/denial and Android job restrictions separately.
- Compare recognition results, corrections and contact links before/after the signed update. Do not clear app data for this test.

Android contracts: https://developer.android.com/develop/background-work/services/fgs/timeout and https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
