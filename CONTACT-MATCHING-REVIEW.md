# Contact-photo upgrade — 0.8.1-contacts (code 37)

Based on the source retrieved from ashwinidhatterwal/faceGallery at commit
871dd672cae1ee1ca5bd1b60758dfdafba52f628. No remote files were modified: the
GitHub integration returned HTTP 403 for branch creation.

## Behaviour

Contacts permission remains optional. Settings > Privacy now exposes automatic
contact-photo matching and a permission button. The existing name/contact
picker can also grant access. With the feature enabled, clear contact portraits
are recognised on device using the same bundled model as gallery faces. Unknown
gallery groups can acquire a contact name and lookup link. Contacts without a
usable portrait keep existing gallery behaviour. Contact portraits do not become
gallery images or additional face groups, and no phone numbers are read.

Named, contact-linked and manually corrected groups are protected. Removing or
changing a link records a durable automatic-link veto on the current group.
Explicit different-person relations and same-photo occupancy prohibit assigning
the same contact to conflicting groups. This upgrade does not force-merge groups
based on a contact name; the existing reversible grouping rules remain in place.

Multiple detected faces, unusable landmarks, poor focus/resolution and extreme
poses are rejected. Contact matching requires cosine similarity above 0.85 and
a margin of at least 0.08 over competing contacts. A singleton needs similarity
at least 0.93; otherwise a second independent gallery reference must score at
least 0.78. These scores are model-specific ranking measures, not probabilities.
Threshold behaviour needs real-device acceptance before publication.

## Reuse and scheduling

Contact photo metadata is read in one local provider query. A cached signature
is reused unless its provider photo revision or recognition model changes. A
SHA-256 of the image bytes avoids repeating inference when only another contact
field changes its modification timestamp. Unusable images are cached as skipped;
transient failures use backoff and stop after three attempts per revision.

Scheduled work processes at most 12 contact portraits per session and obeys the
existing time, battery, heat and pause gates. It reuses a detector and encoder
within the session. A completed contact sweep, including bounded retries, is
required before naming groups so unprocessed portraits cannot hide a competing
contact. Gallery/contact changes wake the existing quiet worker. Per-group match
checkpoints avoid repeating unchanged comparisons. Contact naming does not dirty
the gallery grouping revision or force another recognition sweep.

SQLite schema 7 -> 8 adds two cache/checkpoint tables and a correction-veto column.
Saved gallery detections, signatures, names, memberships and corrections remain
in place. Turning off contact matching clears its portrait signature cache but
keeps editable names and links. Clearing all face results clears the caches too.

## Install and build

Replace the source at the repository root with this ZIP's contents, including
.github. Existing repository signing secrets and the signed-release workflow
are preserved. Run Actions > Build release APK + AAB using the same upload key
as previous releases. A locally generated debug signer may not update an
existing installation; do not uninstall to bypass a signing mismatch when you
want to preserve recognition data. No private credentials are included here.

## Physical acceptance still required

- Allow contacts with a clear portrait, then let normal recognition finish.
  Check that the existing unnamed gallery group gets the correct name/link.
- Try contacts with no picture, two faces, a logo, blur and competing similar
  portraits. They should leave uncertain groups untouched.
- Remove a wrong link and correct a face using existing controls. It must remain
  corrected across restart, changed contact pictures and later gallery imports.
- Change a contact name/photo; check cached reuse and refresh. Revoke permission,
  disable matching, pause background recognition and restart the phone.
- Verify the minified signed release on the real phone, including long-running
  scheduling. Host unit tests do not execute Android ML Kit native inference.

## Automated validation

- Final Gradle build: assembleDebug, testDebugUnitTest, lintDebug, assembleRelease, bundleRelease and lintRelease all succeeded.
- 365 Android/Robolectric tests passed, including 28 new contact-matching tests. No skipped tests or failures.
- Four Python release-verifier tests passed.
- Debug lint: zero errors, 114 warnings; release lint: zero errors, 113 warnings. Existing styling/localisation warnings remain.
- Optimised release APK and AAB passed archive integrity, unchanged model SHA, native ELF 16 KB alignment, APK 16 KB zip alignment and release manifest checks.
- Native ML Kit contact-photo inference and long background scheduling still require real-device acceptance.
- Detailed validation summary: validation/contact-upgrade.json.

