# Face Gallery rc20 / build 60 — recognition audit fixes

This package updates the available rc18 source and includes the zero-face behavior previously described for rc19, together with the recognition audit changes. Application ID remains `com.mosaic.gallery`. Database version increases from 12 to 13 through an additive migration; original photos, recognition signatures, identities, names, contact links and manual corrections are retained. Install an update signed with the existing key and retain app data.

## Zero-face processing

Recognition work is now tracked by transactional SQLite evidence revisions. Inserts/updates/deletions of faces, signatures and identity evidence invalidate matching in the same transaction as those changes. Merely saving a newly checked photo with zero faces does not advance that revision. Removing previously detected faces or invalidating a face-bearing photo still does. Foreground matching checks this revision before running an audit and checkpoints a completed pass. The signature queue also computes its detection-pending set once.

The regression cases seed an existing completed named person and add 20 new photos whose detector returns no faces. Background work must perform exactly 20 detections, zero signature encodes and enter no matching phase. A foreground case with completed zero-face results must likewise skip saved-match audits. These are automated pipeline tests with controlled detections, not a claim about the exact pixels or timing on the reported phone.

## Audit finding resolution

| Finding | Change |
|---|---|
| F01: repeated repair prefixes | Durable cursors and completion markers for saved joins and assignments, scoped to evidence revision. Interruption exits before entering the next stage. |
| F02: repeated refinement graph work | An early eligible-face query avoids graph work when no refinement can apply. A persisted candidate plan is reused while face/identity evidence is unchanged. Provisional-group competitor checks use the exact metric index. |
| F03: provider/inference work under People lock | Background gallery queries, photo detection/signature inference and contact provider/portrait inference run outside the global writer lock. Commits validate cancellation under bounded writer sections. Manual contact sync and diagnostics no longer wrap provider calls in the writer/snapshot lock. Group graph mutations remain serialized. |
| F04: irrelevant retries | Production retry scheduling supplies the current accessible photos. Retry rows for inaccessible or no-longer-relevant face operations do not schedule wakeups. Saved rows may remain for future restored access. |
| F05: misleading completion | Successful work is counted independently of due-now queues. Deferred work shows a waiting state, exhausted errors show a count needing attention, and background retry delays prevent a clean completion notification. |
| F06: swallowed signature failures | Foreground and background signature paths use the same failure helper for decode/recovery errors and save appropriate per-face backoff. Cancellation is checked again under the commit lock. |
| F07: missing comparison dependencies | Group/reference fingerprints include member evidence, including timestamp and pose, as well as vectors. A changed fingerprint invalidates the affected evidence. |
| F08: contacts delaying photo work | Photos and signatures get their batch allowance before contact work. Contacts have a separate bounded turn, a short-lived provider-directory cache invalidated by contact changes/manual sync, and a persisted traversal cursor. |
| F09: Resume ignored while draining | Explicit resume clears the persisted pause before service admission checks. Busy workers retain one queued continuation. Explicit resume clears the edit cooldown without allowing an already canceled background operation to commit. |
| F10: preference/SQLite crash window | Matching revision and completed checkpoint now live in SQLite. Face/identity triggers make invalidation part of the data transaction. SharedPreferences retain consent, user-started and timed-pause preferences, not the grouping evidence revision. |
| F11: full People snapshot requires videos | The full snapshot has a photo/contact scope and its own photo-only metadata stream. Video permission changes and a missing combined media index do not reject it. Selected-only photo access still requires current accessibility checks. |
| F12: blanket pair work and cache cliff | An exact vantage-point metric index supplies plausible pairs without changing score thresholds. Production grouping consumes one bounded neighbor row at a time rather than storing a whole quadratic pair list. A bounded evidence cache reuses unchanged reference pairs across unrelated group changes, including above the old 5,000-group negative-bitset limit. |
| F13: discarded background counts | Background grouping now forwards its available completed/total face counts and measured step estimates. Unknown-size audit/setup work remains indeterminate. |
| F14: detector contention treated as bad photos | Detector-busy and native timeout conditions are classified as worker-level transient errors. They preserve per-photo attempt budgets and schedule continuation instead of recording each waiting image as corrupt. |

Recognition models, score thresholds, explicit separation rules, same-photo conflicts, contact naming gates and correction precedence are retained. The index is an exact candidate filter; final matching still applies the original decision rules.

## Validation

All 549 Android tests passed with zero failures, errors or skipped tests, including 18 new audit regression tests. Release lint passed with zero errors and 158 warnings. Release APK/AAB builds, four release-tool tests, static publishing checks, recognition model/manifest checks, archive integrity and 16 KB native/APK alignment checks passed. Exact results, source hashes and artifact hashes are recorded in `validation/recognition-audit-fixes-rc20.json`. New tests cover both zero-face flows, persistent refinement reuse, transactional rollback and preference loss, retry accessibility and truthful counts, shared detector availability, failure accounting, Resume while draining, background query lock scope, repair cursor advancement, comparison dependency changes, unchanged-pair reuse, exact-index equivalence to exhaustive candidates, contact traversal after database reopen, and full People snapshots under mixed media permissions. Clear-results tests verify that new durable work plans are removed with recognition results. Existing migration tests also verify retained identities and signatures after database version 13.

The prior `RECOGNITION-AUDIT.md` and `audit/recognition-audit-evidence.json` describe the rc18 audit baseline. Its reproduction script targets that earlier code and is not the fixed release's validation command. Use the current Android test suite for these changes.

## Limits and phone acceptance

- An upgraded library receives an initial matching validation because its previous preference-based checkpoint cannot establish the new transactional work state. That validation resumes through saved repair cursors; it is not evidence that a zero-face result invalidated matching.
- Exact metric search can still approach quadratic work for dense/near-identical candidates. Its speed depends on the library; no phone-wide timing guarantee is claimed. Production neighbor-row storage is bounded by the group count.
- The pair evidence cache is bounded and process-local; the grouping cursor and repair/refinement/contact plans are durable. A process restart can rebuild the index and recalculate some pair evidence.
- Media/contact providers and native inference have cooperative cancellation. Native timeout recovery waits for native completion safely; Android still controls background job timing and force-stop recovery.
- Automated tests use controlled face/vector inputs. Recognition accuracy and rendering/performance on actual photos still require handset checks.

Install with the existing signing key, retain app data, let initial migration work finish, then add 20 clear non-face images and verify there is no new saved-match phase. Repeat with real face photos and a modified/deleted face-bearing image. Exercise People while background queries run, pause/resume from the notification during a long operation, process restart, contact changes, mixed video permissions and explicit corrections/undo. Confirm success, deferred retry and permanent-issue counts separately.

Local validation APK/AAB artifacts are unsigned. This source package has not been pushed to GitHub.
