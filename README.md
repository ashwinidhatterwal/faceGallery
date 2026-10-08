## Contact portrait matching — 0.8.1-contacts

Built from the current faceGallery repository, commit 871dd672cae1ee1ca5bd1b60758dfdafba52f628.

Enable contacts access in Settings > Privacy (or the existing name/contact dialog). With **Match contact photos automatically** enabled, background/foreground recognition compares suitable single-person contact portraits against gallery groups. Clear matches name and link unnamed groups. Missing, poor or ambiguous portraits retain existing behaviour. Saved names, contact links and manual face corrections take priority. Remove a wrong contact link through the existing name editor; it will not be automatically reattached.

The cache is persistent, versioned by recognition model and refreshed on contact changes. Unchanged pixels reuse signatures even when a name/phone edit changes the provider timestamp. Skipped portraits are cached; errors back off and stop after three attempts per photo revision. Contact images never become gallery photos or extra face groups. Processing shares existing pause, heat and battery gates and has no new progress notification. Database upgrade 7 → 8 retains existing recognition and grouping results.

The repository's current release signing workflow and secrets are preserved. A locally built debug APK uses the standard local Android debug key and may not update a previously signed installation. Build your signed APK/AAB using **Build release APK + AAB** with your existing repository secrets. Never uninstall a data-bearing installation merely to change signing keys.

# Mosaic Gallery — Android 0.8.0-rc1

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
3. Open the successful run. Download the **Mosaic-Gallery-Release** artifact ZIP.
4. Unzip that Actions artifact to get exactly:
   - `Mosaic-Gallery-Release.apk` (installable release APK)
   - `Mosaic-Gallery-Release.aab` (signed Play bundle)

The APK and AAB are signed using **the same key you provided**. On every manual run, the workflow builds release outputs, runs tests/lint and verifies the APK/AAB archives, signatures, Android manifest and native library alignment before publishing the artifact. If secrets are missing, a clear error explains what to configure; the workflow does not run automatically on push.

## Development requirements

- Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13 and JDK 17.
- Android API 36 and SDK Build Tools 35.0.0; minimum supported Android API 28 (Android 9).
- App ID `com.mosaic.gallery`; version code 37; version name `0.8.1-contacts`.
- `./gradlew :app:assembleDebug` makes a local debug APK, which Android signs with the developer machine's local debug key, not your Play upload key.

## Important release notes

- A correctly built and signed release still needs on-device functional testing, Play Console declarations, and privacy policy publishing before production launch.
- Any previously installed APK signed using a different certificate cannot be upgraded in place with this signed APK. Existing development test builds have a separate signing identity.
- If the app is already live in Play Console, signing an update with an arbitrary newly generated upload key may fail: follow Google's accepted upload-key/reset process.
- Third-party licenses remain under `app/src/main/assets`. The app includes an offline privacy notice there as well.
