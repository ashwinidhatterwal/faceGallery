# Build 50: contact portrait rechecks

Old skipped portrait results without quality measurements receive one bounded repair scan. Recorded rejections and accepted signatures continue to be reused. The same pixel digest no longer suppresses that necessary repair. No model, quality threshold, similarity threshold, grouping policy, or database version change.

Settings → Contact diagnostics → select a contact → Recheck photo processes only that contact. It shares the recognition writer lock, checks permission/consent and battery/temperature, and cancels on dismissal. Temporary failures preserve its saved reference. A successful recheck schedules a complete cached contact sweep before automatic naming; it does not name against one isolated contact or reset other portraits/groups. Detection diagnostics now distinguish zero detected faces from multiple detected faces.

Tests cover one-time legacy repair, accepted signature reuse, forced single-portrait processing, and preservation after temporary read failure. Phone verification remains necessary to determine Papa Ji’s specific quality failure; no successful match is guaranteed.

Validation: 453 unit/regression tests passed, four release-verifier tests passed, release APK/AAB builds and lint completed successfully. Archive, manifest, model checksum and native alignment verification passed. Local release artifacts were unsigned; use the existing GitHub signing workflow.
