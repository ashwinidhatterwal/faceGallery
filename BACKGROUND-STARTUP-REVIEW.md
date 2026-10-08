# 0.8.2 background and startup review

- versionCode 38; existing signing workflow and model remain unchanged.
- Missing photo and contacts permissions requested together on startup. Android may show separate system dialogs. Contacts are optional; denial does not close a gallery with photo access, and optional denial is not repeatedly prompted.
- Existing photo access and selected-photo access are respected; the update requests only missing contacts permission. Permission grants schedule the persisted recognition pipeline immediately.
- Discovery job is no longer idle-only. Existing installs migrate the previous job. Battery/storage constraints remain, and in-session checks stop detection, signatures, grouping and contacts when power or temperature becomes unsafe.
- Safety policy: battery at least 20%, battery temperature below 40°C, Android thermal level below MODERATE, battery saver off. Unknown readings retain prior fallback behavior. Charging does not bypass a known low battery.
- Warm/low-battery sessions save progress and retry later. No exact background-start time can be guaranteed under Android scheduling, Doze, manufacturer restrictions or force stop.
- Existing completed recognition and contact caches are reused. No schema/model/grouping policy change forces re-recognition.

## Phone acceptance

1. Fresh installation: verify photo and contacts system prompts; grant both, close the gallery, and confirm processing continues when cool and above 20%.
2. Existing installation with photo permission only: verify one contacts prompt on launch.
3. Decline contacts: gallery still works and later openings do not prompt again; Settings → Privacy can request it explicitly.
4. Android 14+: selected-photo access only processes allowed photos and preserves identities for inaccessible photos.
5. Low battery / warm phone / battery saver: check progress pauses, then resumes from saved work after conditions recover and Android schedules a job.
6. Force stop is not a valid background test: reopen the app to enable jobs again.

## Automated validation

372 Android tests, zero failures/errors/skips; four Python release checks; release APK/AAB builds; release lint zero errors (114 warnings); native/model/archive alignment checks passed. Physical background endurance testing remains necessary.
