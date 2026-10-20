# Build 56 — People startup and Android gallery integration

Version: 1.0.0-rc16 / 56. Application ID: com.mosaic.gallery.
Baseline: GitHub main, db5d762212efe327cbbef2c70e947f839514dea7 (rc15 / 55).

## People startup

The previous saved snapshot still required deserializing all face rows and reference vectors, loading the full photo index, and hashing its records before displaying profiles. The launch path now reads a separate compact binary snapshot containing one cover, name and count per visible group. It does not read the face database, photo index, contact provider, or original photos before showing the group list. Existing small private cover JPEGs are warmed separately. Full media/database validation continues on a worker. Profile cards are prepared on that worker rather than rebuilding their grouping and ordering on the first UI frame.

The launch snapshot is display-only. It cannot confirm identities, name people, or alter recognition. A tap can open the saved group's folder while current data loads; naming and correction actions use the full data. Permission changes reject incompatible snapshots; selected-only access does not restore the full-library list. Resetting recognition removes both display snapshots and full in-memory People caches. The snapshot uses private no-backup storage and contains no embedding vectors. Existing faces, names, corrections, signatures, model identifiers and recognition thresholds are unchanged.

After installing this update, allow one normal People refresh to populate the new compact snapshot. Old UI snapshots may be rebuilt once; recognition data is not reset or rescanned for this change. Subsequent cold starts can display that saved list while validation proceeds. Actual device latency has not been measured; this is not a promise of a fixed millisecond time or instant first-ever recognition.

## Android integration

- MAIN + APP_GALLERY entry, with the full library visible on this external entry.
- VIEW for local image/video content and file URIs.
- Standard android.provider.action.REVIEW and legacy com.android.camera.action.REVIEW camera actions. The requested capture stays in the viewer even if the MediaStore list has not caught up. Other accessible camera photos/videos are available through the existing viewer.
- GET_CONTENT and legacy PICK media selection. Image/video MIME filters, optional multiple selection, and selected-media read grants are supported.
- An externally granted photo/video can be displayed without requesting broad library access. Its metadata is queried directly from its URI, including Openable-only FileProviders.
- Incoming requests pass through a narrow boundary; private search, people and playlist extras are not accepted from the caller. Network URIs are not registered or accepted. No new Android permissions or network/backend dependency were added.

The app now advertises the Android interfaces needed to be chosen as a gallery/viewer. This cannot force an OEM camera that explicitly opens its bundled gallery to offer a chooser. Android's system Photo Picker is a separate system component and is not replaced by GET_CONTENT support. Secure lock-screen REVIEW_SECURE is deliberately not advertised by the unrestricted viewer; locked-camera behavior must be checked separately. There is no claim that Face Gallery obtains the privileged system-gallery role.

The picker shares only explicitly selected media with the requesting app. The in-app/public privacy text was updated to explain this user action.

## Phone checks

1. Install the signed update using the existing signing key. Keep app data.
2. Open People once, wait for its ordinary refresh, close the app, then reopen and tap People immediately. Verify covers, counts and names appear before background refresh finishes. Try both a warm return and a killed-process cold start.
3. Open a group, search people, rename/correct a face, close/reopen, and check the saved display catches up. Test revoked photo/contacts permissions and selected-only access. Clear recognition results and ensure old groups are not restored.
4. Take a photo/video in the stock camera and tap its recent-capture thumbnail. If Android offers Face Gallery, choose it and select Always when available. Confirm the newest capture is selected and Back returns correctly. If the camera always opens its own gallery, its explicit launch path must be investigated on that phone.
5. In a file manager, open a local image and video with Face Gallery. Test a URI supplied by another app with no broad photo permission. Confirm share/edit/delete failure handling for read-only provider media.
6. In another app's attachment chooser, select Face Gallery. Check single selection, multi-selection, cancellation and MIME-specific selection; the receiving app must read only returned URIs.
7. Check Android 9 and a current Android device, light/dark themes, rotated layouts, and the existing background recognition behavior.

## Validation

- 516 Android/Robolectric tests passed; 0 failures, 0 errors, 0 skipped. The focused startup/integration/cache run also passed all 29 tests.
- Four Python release-tool tests and static publishing checks passed.
- Release APK assembly, AAB bundling and lint completed successfully. Lint: 159 warnings, zero errors/fatal issues. Two warning sites concern compile-time intent string constants from API 29; those strings are inlined and the legacy camera action is also supported on Android 9. This is not a zero-warning claim.
- Release archive integrity, application ID/version, manifest permission restrictions, unchanged recognition-model hash, and native library/16 KB alignment checks passed.
- Actual handset startup timing, stock-camera compatibility, receiving-app URI access and overnight background behavior still need phone acceptance.

Local release artifacts were unsigned validation builds. Use the repository's existing signed GitHub Actions workflow for installable release APK/AAB files, preserving the existing signing key. No signing credentials are included. This source package has not been pushed to GitHub.

## Android contract references

- https://developer.android.com/reference/android/provider/MediaStore#ACTION_REVIEW
- https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_GALLERY
- https://developer.android.com/training/basics/intents/filters
- https://android.googlesource.com/platform/packages/apps/Gallery2/+/refs/heads/android11-mainline-media-release/AndroidManifest.xml
