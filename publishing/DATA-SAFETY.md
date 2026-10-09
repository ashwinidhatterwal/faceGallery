# Data safety review draft

Do not submit “No data collected” merely because INTERNET is removed. The app bundles ML Kit face-detection 16.1.7. Google documents technical SDK diagnostics and per-installation identifiers for bundled features. Removing direct network permission has not been demonstrated to disable every SDK/provider telemetry path. This draft deliberately includes SDK diagnostics until the final release configuration is established. No ads, account, developer analytics server or developer cloud recognition is configured.

| Data / operation | Source behavior | Form guidance |
| --- | --- | --- |
| Photo pixels, videos, face coordinates/signatures | Local device processing and private storage | On-device-only processing is outside off-device “collection”; explain access in privacy policy |
| Contact names, portraits and selected contact lookup links | Optional local access/matching; numerical portrait signatures cached locally | Local-only contact access is not off-device collection; prominently disclose background use |
| Names, grouping corrections, favourites | Local private app storage | No off-device collection implemented |
| SDK performance/error events, device/app configuration | ML Kit documented diagnostics and usage analytics | Conservative draft: collect **Diagnostics**, purpose **Analytics**; review exact SDK data classification |
| SDK initialisation/detection/release events | ML Kit documented API events | Conservative draft: collect **App interactions**, purpose **Analytics**, if this categorisation matches final SDK data |
| Per-installation SDK identifier | ML Kit bundled-feature disclosure | Conservative draft: collect **Device or other IDs**, purpose **Analytics** |
| Sharing/export to another app | Only explicit user-selected Android share/export | User-initiated expected transfers can fall under Play's sharing exception; no automatic media/contact sharing |

For SDK data: Google documents HTTPS encryption and no transfer by ML Kit to third parties. Evaluate whether Google's role in this app meets Play's service-provider exception before selecting sharing answers; Google's own “not shared with third parties” wording does not automatically settle the developer's declaration. The app provides no separate technical SDK-telemetry switch. Do not mark SDK collection optional simply because Android contacts permission is optional. Google says its disclosure page describes latest SDK versions; verify the pinned version's behavior as well.

Before submission, confirm these categories and sharing roles for the actual signed AAB; inspect Play SDK warnings and provider/runtime behavior as needed. If evidence establishes that the configuration sends no data off-device, revise the form accordingly and retain that evidence. Do not claim independently verified security review or deletion of SDK-held diagnostics. Local results can be erased through Clear results; turning off recognition prevents recreation. No account-deletion URL is required solely for a local app with no accounts, but the privacy policy must explain retention/deletion and provide a contact mechanism.

Evidence: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `ContactRecognition.kt`, `FaceStore.kt`, `PRIVACY.txt`, `tools/verify_release.py`.

Official references:
- https://developers.google.com/ml-kit/android-data-disclosure
- https://support.google.com/googleplay/android-developer/answer/10787469
- https://support.google.com/googleplay/android-developer/answer/10144311
