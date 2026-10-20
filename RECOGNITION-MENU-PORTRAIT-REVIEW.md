# Face Gallery rc18 / build 58

Updates the rc17 source in this workspace. Application ID remains `com.mosaic.gallery`; update with the existing signing key and keep app data.

## Recognition placement and continuation

The small Start card appears only before recognition has first started. Its visibility uses the durable started preference, including when People is created before its resume callback, so returning, completing a scan, restarting the process or pausing does not bring the card back.

After starting, the People three-dot menu includes Recognition and its current status. It shows measured step counts while active, the saved resume date/time while paused, or Watching for new photos while idle. Opening that entry displays live counts and progress with Pause for 24 hours / Resume. The status observer is removed when the dialog closes or the activity is destroyed.

Start has a guard for an active foreground/background run and for an already enabled session. Repeated taps keep the existing run and ensure its automatic schedules instead of launching another scan. Resume uses saved photo fingerprints, detection results and signatures. Neither action clears recognition, names, contact links or corrections. Unchanged completed photos are excluded from normal detection/signature work; a new or modified photo is processed as needed. Existing contact updates, bounded refinement and grouping rules remain in place.

After the initial start, the existing MediaStore content-trigger job, persisted recovery job and periodic fallback continue without another Start tap. The content watcher is rearmed after a run. Android controls when background jobs execute; force-stop prevents execution until the app is reopened. The timed 24-hour pause behavior from rc17 is retained.

## Portrait viewing

The viewer previously reserved its header and bottom toolbar/filmstrip height inside the photo viewport, even when controls were hidden. A tall portrait could therefore be height-constrained and have unnecessary side margins.

With no drawer open, the photo pager now occupies the entire viewer. Controls overlay it. Videos retain their existing space for playback controls above the filmstrip and action bar. Photo scaling continues to preserve the full image and its proportions; a photo taller or wider than the screen's proportions may still need natural letterboxing. No stretching or automatic crop was added. People and Tags drawers still animate the photo into their available region. The Tags button follows the other controls when they are hidden.

## Validation

All 531 Android tests passed with no failures, errors or skipped tests. Release lint completed with zero errors and 160 warnings. Release APK/AAB builds, four release-tool tests, static publishing checks, model hash, manifest rules, archive integrity and 16 KB native/APK alignment checks passed. Exact results and hashes are recorded in `validation/recognition-menu-portrait-rc18.json`. Tests cover first start/repeated tap/reopened and paused screens, live recognition status, new-photo-only inference after completion, full portrait viewport and width on Android 9 and Android 15, plus the existing drawer and gesture regressions.

## Phone acceptance

- Install an update signed with the existing key; retain data. Start once in People and confirm the card disappears. Open the three-dot menu during work, after completion, after reopening the app and during a timed pause.
- Pause/Resume in the status panel and notification; confirm counts continue from saved work. Add or take a new photo after completion and verify automatic processing without another Start tap.
- View tall portraits with controls visible and hidden. Check left/right image edges, pinch, rotate, swipe, video controls, and opening/closing People and Tags drawers. Repeat with gesture navigation, button navigation, display cutouts and landscape orientation.

Physical-device rendering and background timing remain handset acceptance checks. Local validation release artifacts are unsigned. This source has not been pushed to GitHub.
