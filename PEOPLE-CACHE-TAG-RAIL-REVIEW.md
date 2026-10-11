# Build 55 — Tags, Albums rail, search and persistent People cache

Version 1.0.0-rc15 / 55. Application ID remains com.mosaic.gallery. Install a signed update with your existing signing key; keep app data.

## Changes

- Tags opens one focused, themed field with compact, horizontally scrollable suggestions. The check button or keyboard Done saves pending input. Bulk editing applies additions/removals as deltas, preserving each photo's other tags. The optional Place field and descriptive text were removed; previously saved place metadata remains searchable.
- Albums shows tag folders in a horizontal rail above physical albums. Each folder has four round previews when available, a name and count. Covers use accessible media only. Tag folders use a short fade to avoid geometry jumps as the rail appears/disappears.
- Search uses a rounded field and compact themed filter chips. Search text is prepared once per index/media/timezone/locale/day and reused while typing. Tag-only changes reuse the identity index. A verified People snapshot can supply search identities without a second graph read.
- People list and person folders share one cache. A private saved snapshot restores display metadata at startup, before provider/face database work. Unchanged snapshots are verified with a transactional database revision and media fingerprint, then reused. Concurrent readers share a single refresh path. Reference vectors are rebuilt only for affected groups; unchanged references are retained. Graph-wide relationships, provisional status and name suggestions still require validation when identities change. The existing card diff updates changed cards only.
- Full-access face thumbnails survive unrelated media changes and ordinary tab switches. Bounded private JPEG previews speed later cold starts. These are UI previews only; recognition continues using the original recognition inputs.
- Exported recognition diagnostics include snapshot build/reuse/restore/save and reference build/reuse counters. Counters contain no names, photos or media URIs.

## Compatibility and safety

Face database migration 11 → 12 adds revision/source bookkeeping and triggers. It retains faces, embeddings, names, contacts, group relationships, corrections and checkpoints. Recognition model identifiers and contact suggestion threshold (0.60) remain unchanged; the upgrade does not request a fresh recognition run. Transactional revision changes roll back with failed writes and ignore unchanged updates. Recognition checkpoint writes do not invalidate the display snapshot.

Saved UI snapshots are private and excluded from backup. Permission changes prevent reuse, and selected-only media grants never restore the full saved snapshot. Changed source fingerprints reject stale saved media. Contact changes invalidate saved display metadata. Snapshot corruption or incompatible serialization falls back to a fresh read. Face previews stay in private cache storage and are not shared or used for inference.

## Validation

- 503 Android/Robolectric tests passed, 0 failures/errors/skips (15 new tests).
- Four Python release-tool tests passed; static publishing checks passed.
- Release APK assembly, AAB bundle and lint completed successfully. Lint: 151 warnings, 0 errors/fatal issues. Existing warnings remain; this is not a zero-warning claim.
- Release archive, manifest, model hash and native alignment checks passed.
- Local release artifacts were unsigned validation builds; use the owner's GitHub signing secrets for installation and Play upload.

## Phone acceptance checks still required

Physical device cold-start latency, frame smoothness, keyboard placement with large fonts/rotation and extended background recognition were not measured here. After the signed update, open People once to create the saved snapshot, then close/reopen the app and compare People/folder loading. Check tag entry/Save/Done, multi-photo tag edits, rail scrolling/folder opening, search filters, and permission revocation. Existing background recognition battery/temperature/pause behavior is unchanged.
