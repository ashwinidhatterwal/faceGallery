# Face Gallery 1.0.0-rc3 — build 43

- Main Photos now defaults to camera media, with remembered All photos and videos / Only camera choices in its anchored menu.
- Filter uses cached metadata only. Albums, recognition and contact matching retain the full accessible library.
- Removed the redundant People action from the main gallery menu.
- Video controls have a 56dp play target with a 32dp icon, a separate 48dp mute target, a 64dp themed bar and 10,000 timeline steps.
- Visible playback progress refreshes at 50ms; paused controls at 500ms. Hidden controls and released players stop the progress timer. Icons are reused until the playback/mute state changes.
- Mute is restored alongside position and play/pause state, including activity recreation.
- Recognition models, matching policy, permissions, application ID and database remain at the existing release baseline.

Validation: 410 Android tests and four Python release-verifier tests passed. Release APK/AAB compilation, lint (zero errors), archive/native alignment and publishing checks passed. Local outputs are unsigned. Actual control views were rendered and reviewed in light/dark at 320dp width.

Physical-device review of playback, gestures and long-running recognition remains necessary before production.

## Earlier release preparation

# Release preparation review

Candidate **1.0.0-rc2 (42)**, based on delivered **0.9.1-media-fixes (40)**. Existing application ID, face database schema, recognition model, matching thresholds and release signing secret names are retained.

## Changes

- Added first-launch/update disclosure before Android permissions. An explicit choice authorizes automatic photo recognition and contact portrait matching. Browse only preserves gallery use without automatic recognition or contact permission requests. Dismiss/back is not consent.
- Background job execution, boot recovery, contact matching and explicit scan service entry are gated by the saved consent. Accepting an upgrade explanation preserves existing permanent/24-hour pauses and existing recognition results; it does not clear or regenerate saved identities.
- Automatic gallery startup schedules persisted jobs; it no longer launches a foreground service or asks for notification permission automatically. Only explicit Recognition tools operations use the foreground service and its required notification.
- Recognition and contacts settings and explicit scan controls can show the explanation when the user previously chose Browse only. Manual scan resumes processing when explicitly chosen, as before.
- Updated in-app policy; generated public policy-page source and configurable support details. Added listing copy, original vector-derived store graphics, declaration drafts, screenshot capture instructions and physical acceptance guide.
- Signed GitHub workflow now checks static Play materials before building; existing keystore/secrets handling is retained. It still runs Android tests, lint, release APK/AAB generation and signature/native/model/manifest integrity checks.

## Validation and limits

406 Android tests and 4 release-verifier tests passed. Release APK/AAB generation and integrity verification passed. Release lint has 0 errors and 129 warnings. Static listing/artwork checks passed. See `validation/play-preparation.json` for final results from this preparation. Local release verification is unsigned; the owner-controlled GitHub workflow must produce the signed upload bundle. No private key was available here. Prior validation files refer to older builds and are historical.

No phone/emulator acceptance, Play upload, public policy hosting, SDK telemetry audit or Google approval has been performed here. `PHONE-ACCEPTANCE.md` records the remaining functional tests. `DATA-SAFETY.md` records the unresolved SDK-specific declaration decision. Public support email is configured. Publish and verify the policy URL, capture real screenshots and an FGS demonstration before submission. Do not advertise a consent feature or processing result that has not been validated on your release-installed phone.

Store assets are branding artwork, not fabricated screenshots. No family/contacts imagery is included in the new promotional assets. Third-party notices and the bundled model/license remain unchanged.

## Face Gallery branding (rc2 / code 42)

Updated the launcher label, About screen, first-use explanation, privacy text, store listing, feature graphic and signed-artifact filenames. Public support contact is ashwinidhatterwal@gmail.com. Application ID remains com.mosaic.gallery so the rename retains the existing install/data path. Prepared docs/index.html, docs/privacy.html and docs/.nojekyll for GitHub Pages. Direct upload was rejected by GitHub with HTTP 403; the policy URL is not yet live.
