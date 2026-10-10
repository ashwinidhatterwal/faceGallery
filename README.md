# Face Gallery — Play release candidate 1.0.0-rc9 (49)

Start with [publishing/START-HERE.md](publishing/START-HERE.md). This source includes store artwork, listing copy, policy/declaration drafts and release checks. Physical-device acceptance, public support details, privacy hosting and Play review remain owner steps.

## People grid suggested labels — 1.0.0-rc7

Unnamed profiles in the People grid show their best suggested name followed by `?`, using the same contact/manual-group suggestions as the photo swipe-up panel. Saved names keep their plain labels; profiles without a suitable suggestion keep their Person label. Tapping an uncertain profile opens the identity picker for confirmation. Suggestions do not save names or merge groups.

Profile suggestions are computed in a batch on the existing worker and retained in the People revision/media/access cache. Switching sections and typing a search reuse those labels without portrait inference or graph writes. Contact/signature/name changes invalidate the cache through the existing revisions.

## Suggested names and manual contact sync — 1.0.0-rc6

Uncertain faces can suggest up to three names from manually named gallery groups and cached contact portraits. The photo's people panel shows the best suggested name with a question mark; tap an unnamed/temporary face in that panel or People to open the shared “Who is this?” picker. Suggestions do not name, merge or promote a group until you confirm them. Contact-linked gallery groups appear once under their saved name. Explicit separations, conflicting contact links, unavailable gallery references and excluded faces are respected. Contacts without a usable portrait cannot provide a face-based suggestion.

**Settings > Sync contact photos**, also available in the People menu, performs a cancellable contact-only check. It uses the existing database writer lock, portrait/signature caches, comparison caches, retries and battery/thermal gates. It can run while automatic recognition or automatic contact matching is paused, provided recognition consent and contacts permission remain granted. It never switches automatic processing on/off, clears gallery recognition or forces unchanged portraits through inference. Leaving the screen cancels the visible check; committed results remain cached. Background schedules remain in place. A completed check updates the shared contact checkpoint so automatic work can reuse it.

No model change or database migration is introduced in build 46. Existing photos, signatures, saved names, contacts and corrections are preserved. The strict automatic naming thresholds remain unchanged; similarity suggestions require your confirmation.

## Contact portrait recovery — 1.0.0-rc5

Contact-specific alignment accepts a bounded amount of missing edge background for tight portraits. Gallery alignment, model and matching thresholds are unchanged. Previously rejected portrait-v2 references are reconsidered once; successful v2 signatures are reused without reading or encoding again. Unchanged skipped portraits and unsuccessful unassigned-face comparisons remain cached. Contact changes, new signatures and saved evidence trigger only the needed work. Interrupted work does not commit a rejection or comparison result.

Database 8 → 9 adds contact rejection reasons and a face-comparison cache without clearing gallery faces, signatures, names or corrections. Export recognition report now includes aggregate contact permission, processing and rejection information, without contact names, lookup links, images or vectors. Genuine identity matches still require gallery evidence; a contact portrait alone does not become a gallery group.

## Playback and grid polish — 1.0.0-rc4

Transparent video controls have a full-width gliding timeline, combined elapsed/total time, play/pause and mute. Timeline dragging reserves its gesture instead of paging to another item. The anchored menu is compact, with one changing Camera/All media action and a refresh icon. Scrolling Photos reveals a small fading handle at the right edge; dragging it jumps through the current media view with a date preview and reuses cached media.

## Contact portrait matching — 0.9.1-media-fixes

Built from the current faceGallery repository, commit 871dd672cae1ee1ca5bd1b60758dfdafba52f628.

Enable contacts access in Settings > Recognition and contacts (or the existing name/contact dialog). With **Match contact photos automatically** enabled, background/foreground recognition compares suitable single-person contact portraits against gallery groups. Clear matches name and link unnamed groups. Missing, poor or ambiguous portraits retain existing behaviour. Saved names, contact links and manual face corrections take priority. Remove a wrong contact link through the existing name editor; it will not be automatically reattached.

The cache is persistent, versioned by recognition model and refreshed on contact changes. Unchanged pixels reuse signatures even when a name/phone edit changes the provider timestamp. Skipped portraits are cached; errors back off and stop after three attempts per photo revision. Contact images never become gallery photos or extra face groups. Processing shares existing pause, heat and battery gates and has no new progress notification. Database upgrade 7 → 8 retains existing recognition and grouping results.

The repository's current release signing workflow and secrets are preserved. A locally built debug APK uses the standard local Android debug key and may not update a previously signed installation. Build your signed APK/AAB using **Build release APK + AAB** with your existing repository secrets. Never uninstall a data-bearing installation merely to change signing keys.

## Build and signing

Minimal GitHub source package. The repository contains application source, Android resources, Gradle wrapper, one manual GitHub Actions release workflow, and release verification tests. **No signing credentials, debug keys, prebuilt APKs, or AABs are included.**

## Upload source to GitHub

Unzip the package on your computer and upload **its contents** to the root of your GitHub repository, including the hidden `.github` directory. Do not upload the ZIP file itself as a repository file. Existing obsolete `.github/workflows/android.yml` and `.github/workflows/release.yml` should be deleted, leaving only `.github/workflows/build-release.yml`.

## Generate your own signing key

Create and safely back up your own signing key, for example with Android Studio's **Build > Generate Signed Bundle / APK > Create new** wizard. Keep the keystore and passwords private. Never commit the keystore to this repository. If this package name has already been enrolled in Google Play App Signing, verify the upload certificate remains consistent with the Play Console's accepted upload key before switching to a new key.

## Add exactly four GitHub repository secrets

GitHub repository > **Settings > Secrets and variables > Actions > New repository secret**:

| Secret | Value |
| --- | --- |
| `UPLOAD_KEYSTORE_BASE64` | Base64 content of **your** `.jks` / `.p12` keystore file (single-line text) |
| `UPLOAD_STORE_PASSWORD` | Your keystore password |
| `UPLOAD_KEY_ALIAS` | Your key alias |
| `UPLOAD_KEY_PASSWORD` | Your key password |

Generate the Base64 text from **your own key**:

- **PowerShell (Windows):** `[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\path\to\upload-key.jks'))`
- **macOS:** `base64 -i /path/to/upload-key.jks | tr -d '\n'`
- **Linux:** `base64 -w 0 /path/to/upload-key.jks`

Paste the resulting characters into `UPLOAD_KEYSTORE_BASE64` (not the literal command).

## Get exactly two release files

1. Open the GitHub repository's **Actions** tab.
2. Choose **Build release APK + AAB** and click **Run workflow**.
3. Open the successful run. Download the **Face-Gallery-Release** artifact ZIP.
4. Unzip that Actions artifact to get exactly:
   - `Face-Gallery-Release.apk` (installable release APK)
   - `Face-Gallery-Release.aab` (signed Play bundle)

The APK and AAB are signed using **the same key you provided**. On every manual run, the workflow builds release outputs, runs tests/lint and verifies the APK/AAB archives, signatures, Android manifest and native library alignment before publishing the artifact. If secrets are missing, a clear error explains what to configure; the workflow does not run automatically on push.

## Development requirements

- Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13 and JDK 17.
- Android API 36 and SDK Build Tools 35.0.0; minimum supported Android API 28 (Android 9).
- App ID `com.mosaic.gallery`; version code 49; version name `1.0.0-rc9`.
- `./gradlew :app:assembleDebug` makes a local debug APK, which Android signs with the developer machine's local debug key, not your Play upload key.

## Important release notes

- A correctly built and signed release still needs on-device functional testing, Play Console declarations, and privacy policy publishing before production launch.
- Any previously installed APK signed using a different certificate cannot be upgraded in place with this signed APK. Existing development test builds have a separate signing identity.
- If the app is already live in Play Console, signing an update with an arbitrary newly generated upload key may fail: follow Google's accepted upload-key/reset process.
- Third-party licenses remain under `app/src/main/assets`. The app includes an offline privacy notice there as well.

## Startup and quiet background work (0.8.2)

On first opening, Android asks for photo/storage access and optional contacts access. Existing installs ask for missing contacts access once. Declining contacts keeps the gallery usable; enable it later in Settings → Privacy. On Android 13+, access covers permitted photos and videos, not all files. Android 14+ can grant selected media only.

Automatic recognition, grouping and contact portrait matching use saved progress and quiet persisted jobs after closing the screen. Work pauses below 20% battery (even while charging), at battery temperature 40°C or higher, at Android thermal status MODERATE or higher, or in battery saver. The periodic fallback no longer requires device idle; existing idle-only jobs are migrated. Warm/low-power jobs retry after 15 minutes without losing completed work. Android controls job timing; force stop or restricted battery settings can prevent execution until the app is reopened or restrictions are removed. No background progress notification is added.

## Photos and videos (0.9.0)

The existing gallery and albums now include local videos. A small play badge distinguishes their thumbnails. The viewer autoplays the selected video and provides themed play/pause, timestamps and a draggable seek bar. Swipe between photos and videos normally; only the visible video owns a decoder. Leaving the viewer releases video/audio resources and keeps the current position for returning to that viewer. Supported formats/codecs depend on the device. Photo editing and face recognition remain photo-only.

Android 13+ requests READ_MEDIA_IMAGES and READ_MEDIA_VIDEO. Android 14+ also supports selected media. An existing install asks once for missing video access; declined video access leaves photo browsing available. Cached metadata is scoped to the actual permissions. Video-only access supports browsing without enabling photo recognition.

Gallery, people, viewer and selection menus use the same themed action panel. Settings opens a dedicated screen for recognition/contacts, people management, tools, permissions and about information. The launcher icon is adaptive, with a themed monochrome variant; editable SVG geometry and a PNG preview are in design/.

The notification's Pause for 24 hours action schedules a persisted next-day wake-up. Restarting the phone restores the remaining delay. Once it expires, recognition resumes from saved work under the existing safety gates. Turning off Background recognition in Settings remains a permanent pause and cancels that timer. Android's scheduling and battery restrictions can delay the actual retry.

## 0.9.1 repairs

Version code 40 fixes video access on updates, tracks permission changes and video events, anchors themed menus to their buttons, preserves Search profiles across internal navigation, and repairs contact portrait reading and bounded background scheduling. See `MEDIA-FIXES-REVIEW.md` for details and phone acceptance.

## Publishing preparation (1.0.0-rc2)

A first-launch/update disclosure precedes permission requests. Automatic photo recognition and contact matching require an affirmative choice. Browse only leaves automatic work disabled; existing names/results are retained. Automatic work uses persisted jobs, while foreground scans require an explicit recognition control. Consent preserves an existing permanent pause. See publishing/RELEASE-REVIEW.md.

### Optional contact photo (build 48)
When manually naming a photo face or a People group with a contact that has no photo, the app offers a themed crop preview. Use photo requests WRITE_CONTACTS on demand and adds a square JPEG to an editable local raw contact. Existing contact portraits are guarded by an asserted provider batch and never overwritten. Not now keeps the gallery link without a contact write. Selection reuses saved face quality/pose/focus scores and current accessible media; stale detections are excluded. It does not rerun detection or embeddings. Contacts account sync may upload the saved contact photo independently of Face Gallery. Real device/provider acceptance checks are listed in publishing/PHONE-ACCEPTANCE.md.

### Contact diagnosis (build 49)
Settings > Contact diagnostics shows named local portrait status and best cached group similarity. People > Export recognition report now includes pseudonymous contact entries and batch stage checkpoints. New/changed portrait scans record dimensions, face size, focus, landmark count, pose and the failed quality checks. The portrait policy, embedding model, recognition thresholds and retry rules are unchanged. The additive database v10 migration preserves all cached results. Old rejection records explicitly have no individual measurements and are reused. Unnamed groups can suggest cached contacts using their other accessible reference photos even if the currently selected face has no signature.
