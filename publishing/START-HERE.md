# Publish Face Gallery

Source release candidate: **1.0.0-rc3**, version code **43**, application ID **com.mosaic.gallery**. This package prepares the app for Play testing and submission; production approval is not yet established. Use your existing Play developer account.

## 1. Publish the prepared privacy page

The listing and in-app/public policies now use **Face Gallery** and **ashwinidhatterwal@gmail.com**. The proposed privacy URL is **https://ashwinidhatterwal.github.io/faceGallery/privacy.html**.

The policy HTML is complete, but GitHub rejected this session's upload with HTTP 403, Resource not accessible by integration. No page has been published. Follow `GITHUB-PAGES.md`: upload the prepared `docs` folder to your repository, then choose **Settings > Pages > Deploy from a branch > main > /docs > Save**. Open the live URL signed out and verify the name/email before putting it in Play Console. The page must be public, readable without login, unblocked geographically, and not a PDF.

To change your public details later, run `python3 tools/prepare_play_listing.py --email YOUR_PUBLIC_EMAIL --developer "YOUR_PUBLIC_DEVELOPER_NAME" --privacy-url https://YOUR_PUBLIC_PRIVACY_PAGE` and rebuild so the in-app and public policy remain aligned.

## 2. Build with your existing key

Upload the source contents to the root of `ashwinidhatterwal/faceGallery`, including `.github`. Keep your repository signing secrets. Run **Actions > Build release APK + AAB > Run workflow**. Download and extract the successful **Face-Gallery-Release** artifact.

- **Face-Gallery-Release.aab** is the file to upload to Google Play.
- **Face-Gallery-Release.apk** is for direct testing. An unsigned validation APK cannot be installed.
- Never commit your JKS, passwords or base64 key. Keep a secure offline backup.
- Repository secrets are scoped to that repository; calendar secrets are not automatically available here. The existing workflow expects `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`.
- If this package is already in Play, use its accepted upload key and a version code higher than every previously uploaded code. If 42 has already been used, increase it before building. Do not change the application ID after the first upload.
- Enrol in Play App Signing on first upload. Google-managed app signing can use a distinct app signing key; your JKS then serves as the upload key. Keep these roles distinct when diagnosing update installation errors.

## 3. Start with an internal test

In [Play Console](https://play.google.com/console), select **Create app** if Face Gallery has no entry. Use **Face Gallery**, English (United States), App, and your chosen pricing. Confirm required declarations. Choose free only if that is your intended permanent pricing model; Play does not let a free app become paid.

Open **Testing > Internal testing**, create a release and upload the **signed AAB**. Add tester emails, save and roll out the internal test. Test the Play-installed version through its opt-in link. Internal testing is the fastest way to check signing, device-specific delivery and installation. Do not uninstall your only data-bearing installation just to resolve a certificate mismatch; use a separate test device/profile.

Complete `PHONE-ACCEPTANCE.md`, inspect the Play pre-launch report, crashes, ANRs, permission notices and bundle compatibility. Android tests do not substitute for real video decoding, recognition quality, battery/thermal behavior or overnight background scheduling.

## 4. Complete the listing and declarations

Paste fields from `store-listing.json`. Upload `assets/play-icon-512.png` and `assets/feature-graphic-1024x500.png`. Capture at least two genuine app screenshots; see `SCREENSHOTS.md`. Use your real support email and the verified public privacy URL.

In **App content**, complete privacy, ads (**No**), app access (no account/login; local media permissions required), content rating, target audience, Data safety and any permissions/foreground-service forms presented. Use `DECLARATIONS.md` and `DATA-SAFETY.md`; these are review guidance, not an automatic questionnaire import. Answer content-rating questions truthfully. Suggested audience: adult general users, if that matches your actual intent; do not target children without a separate Families-policy review. No payments, ads, chat or account feature is implemented.

Record a short demonstration of an explicit scan and its notification for the foreground-service declaration. Do not claim automatic jobs require immediate foreground service execution.

## 5. Closed testing and production

If your personal developer account was created after 13 November 2023, Google requires this app's closed test with at least **12 continuously opted-in testers for 14 days** before applying for production access. Publishing your calendar app does not establish Face Gallery's test results. Follow the eligibility status shown on this app's Console dashboard.

Resolve tester feedback and pre-launch issues, complete required declarations and apply for production access if prompted. Promote the tested AAB to production, select countries, review the release and send it for Google review. Use managed publishing if you want to choose when approved changes go live. Start with a staged rollout where available and watch Android vitals and reviews.

## What remains before production

Public privacy hosting; real screenshots and FGS demonstration; successful signed CI build; Play-installed phone acceptance including overnight recognition; final Data safety decision for ML Kit diagnostics; Console policy review and any required closed test. No approval, public privacy hosting or Play upload has been performed by this package.

## Official references (checked 9 October 2026)

- [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [16 KB compatibility and testing](https://developer.android.com/guide/practices/page-sizes)
- [Create and set up an app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [New personal account testing](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
- [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Photo/video permission policy](https://support.google.com/googleplay/android-developer/answer/16558241)
- [FGS declaration](https://support.google.com/googleplay/android-developer/answer/13392821)
