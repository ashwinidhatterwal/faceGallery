# Face Gallery recognition audit

Date: 21 October 2026. Scope: detection, signature encoding/refinement, grouping and repair, contacts, persistence, scheduling, pause/resume, progress and People caching.

## Result and source boundary

**14 additional findings: 3 high-priority performance defects, 8 medium-priority defects/risks, 2 medium-priority scalability/retry weaknesses, and 1 low-priority progress weakness.** The main remaining problem is that recognition is incremental at photo inference, but several later stages still rebuild or audit the whole face library.

The available working tree and source ZIP are **rc18 / build 58**. Production Kotlin files match the previously delivered rc18 ZIP byte for byte; ZIP SHA-256: `3b4dce800e0a0a698a8d2b6b948b72e21dea386b1c37aa57afc35ac49a1a04cc`. The rc19 source/ZIP from the previous turn is absent from this workspace. Findings below distinguish the three fixes described in that turn from additional issues. Applicability to rc19 is based on those described edits, not an independent inspection of its missing ZIP or an installed APK.

Production code was not changed by this audit. The evidence script and this report were added. Existing rc18 validation records 531 passing Android tests; **that suite was not rerun for this audit** and does not establish that the findings below are fixed.

## Priority summary

| ID | Priority | Finding | Confidence/evidence |
|---|---|---|---|
| F01 | High | Interrupted saved-match audits restart at the beginning | Source-confirmed; bounded-work model E03 |
| F02 | High | Refinement checks repeatedly rebuild and compare unchanged groups | Source-confirmed; operation count E04 |
| F03 | High | Background media/contact work holds the global People lock | Source-confirmed lock scope; handset latency unmeasured |
| F04 | Medium | Retry scheduling includes inaccessible or irrelevant photos | Actual SQLite reproduction E01 |
| F05 | Medium | “Up to date” and checked counts can hide pending failures | SQLite + completion-branch reproduction E02 |
| F06 | Medium | Foreground signature decode failures bypass retry accounting | Source-confirmed exception path |
| F07 | Medium | Comparison checkpoints omit evidence used by matching | Source dependency proof E05; cache integration test still needed |
| F08 | Medium | Contact work can consume the photo-detection budget | Source + scheduling model E07; device frequency unmeasured |
| F09 | Medium | Notification Resume can be ignored during background pause drain | Source branch ordering E06; Android interaction test needed |
| F10 | Medium | Grouping revision is not committed with face data | Crash-consistency risk; not crash-injection tested |
| F11 | Medium | Full People snapshots still depend on unrelated video permissions | Source-confirmed permission predicate |
| F12 | Medium | Pair matching remains quadratic and broadly invalidates reuse | Source-confirmed scalability weakness |
| F13 | Low | Background grouping discards available progress counts | Source-confirmed UI/data weakness |
| F14 | Medium | A busy detector can consume individual photos' retry budgets | Source-confirmed conditional failure path; native hang not reproduced |

Priorities reflect user impact, not measured occurrence rates.

## Findings

### F01 — Saved-match repair cannot resume its audit position

`PeopleGrouping.kt:133` starts every run with `repairJoins()` and `repairAssignments()`. In `PeopleStore.kt:246` and `:262`, those functions recreate their rows/groups and start their loops at the first edge/face. Neither retains a cursor or an “already audited for this evidence revision” checkpoint. The resumable comparison cursor applies to the later pair-comparison phase, not these audits.

When unchanged, valid joins take longer to audit than a slice allows, the next slice repeats the same prefix. The run can spend repeated slices on Checking saved matches without completing the audit. `repairJoins()` also rebuilds components and capsules for each audited edge, making the prefix expensive. The repair setup and some capsule building do not check cancellation internally.

**Fix direction:** checkpoint repair stage, evidence generation and cursor; reuse graph/reference snapshots within a slice; audit affected joins/assignments where safe. Test cancellation before the audit end and prove a later run reaches later items without weakening manual vetoes. E03 demonstrates the restart condition; it is not a physical timing measurement.

### F02 — “Is refinement needed?” performs whole-library matching work

`SignatureRefinement.kt:6` reads all members, builds capsules/components and invokes `IdentityEvidence.provisional()` before filtering for an accessible photo and retry eligibility. `IdentityEvidence.kt:29` can compare each unestablished group with every other group to decide provisional status.

`AutoPeople.kt:135–150` can call this path once to pick refinement work and again to decide whether another job is needed. `AutoPeople.hasPending()` and People display rebuilding also use related whole-graph work. There is no revision-based reuse of the refinement-candidate result. This remains after a zero-face result stops dirtying grouping.

In E04's 256 dissimilar, clear singleton groups, the provisional check requires **65,280 vector comparisons per invocation**, or 130,560 for two invocations, before counting other graph queries. This is an operation count, not an Android speed benchmark.

**Fix direction:** maintain a durable/refreshed refinement queue, cache candidate/provisional results by actual face/identity changes, filter ineligible/completed retries early, and share one graph snapshot per batch.

### F03 — Background work can still block People readers

`AutoPeople.kt:108` wraps the entire background batch in `FaceWork.write`, including `photos(run.signal)`, retaining records, contact provider queries/portrait inference, detection, signatures, refinement and grouping. `FaceWork.read()` uses the same exclusive lock. A slow gallery/contact provider therefore blocks People reads before it can even reach a safe-yield check.

The 20-second deadline is cooperative; it does not preempt a blocking provider call. `ContactPhotoSync.kt:34` and diagnostic operations have related provider-under-lock paths. This is distinct from the foreground media-query lock problem fixed earlier.

**Fix direction:** query providers and perform inference outside the database critical section, then validate permissions/generation and commit bounded results under the writer lock. Share cancellation and run ownership so edits cannot race with stale commits.

### F04 — Hidden retry rows can keep background work waking indefinitely

`FaceStore.kt:56` computes the earliest retry from all `operation_retries` rows for the model. It does not join the accessible photo set or check that the operation is still needed. Selected-only access intentionally retains unavailable records, and an inaccessible photo cannot consume its next attempt.

An overdue, retained retry can consequently produce another 60-second delay on every run despite there being no accessible photo to process. E01 executes the production SQL with exactly this state.

**Fix direction:** schedule retries only for accessible, still-pending operations. Suspend retained inaccessible retries until access/media changes, and remove irrelevant retry rows safely.

### F05 — Completion and counts conflate success with “nothing due now”

`AutoPeople.kt:155–163` shows finished/up-to-date when `more` is false and no exception escaped, even when `retryDelay` is non-null. It then schedules that deferred retry. `progress()` computes checked photos from `pending(..., false)`, which excludes errors still in backoff; failed photos can therefore count as checked. Exhausted failures can also leave the app presenting a clean completion.

E02 uses the production retry query for an errored photo with a future retry: no item is due now, yet the completion branch selects finished. Foreground completion similarly does not reconcile successful, failed and deferred totals before saying Recognition finished.

**Fix direction:** distinguish successfully checked, failed, deferred and inaccessible counts; show Waiting to retry or Completed with issues; reserve Up to date for successful completion of the stated scope.

### F06 — Foreground failures before per-face encoding are swallowed

`FaceScanService.kt:114` and `:135` catch and discard failures from `SignatureEngine.process()`. In `FaceEncoder.kt:52`, image decoding occurs before `SignatureProcessing.run()` can save a per-face error. A decode/landmark-recovery exception can therefore leave the photo pending without adding an encoding retry/backoff entry, while the outer workflow continues toward a successful finish.

The background signature path has an explicit failure handler, so the two workflows differ.

**Fix direction:** use shared error handling for decode, detector recovery and encoding; persist an appropriate failure/retry result without consuming attempts on cancellation. Test throwing before the first face is encoded.

### F07 — Comparison generations omit time/pose dependencies

`PeopleGrouping.kt:125–129` keys resumable comparisons by policy, relations, names, contacts, group photos/leaves, reference keys and vectors. It omits reference timestamps and pose values. `IdentityEvidence.independent()` uses timestamps and yaw to decide whether two photos supply independent evidence; `GroupRules.agreement()` depends on that decision.

The same references/vectors with changed capture-time metadata can keep the same generation while their agreement eligibility changes. E05 verifies that dependency mismatch. A persisted cursor or in-memory pair result can then reuse stale evidence, potentially affecting merges.

**Fix direction:** include every matching dependency or a transactional reference-evidence revision in the generation; test unchanged vectors with changed capture times/yaw and process recreation.

### F08 — Contact processing precedes and can starve new photo work

`AutoPeople.kt:116` handles contact portraits before photo detection, inside the same 20-second batch. The detection deadline at `:125` is capped at batch start +10 seconds. If contact work takes 12 seconds, detection gets no remaining allowance, even though the batch still has time. Pending contact batches repeat this ordering.

Contact scanning also queries the complete address book and walks earlier cached portraits to reach later due items each batch. Large address books can amplify this overhead. E07 demonstrates the budget calculation; it does not assert that all devices take 12 seconds.

**Fix direction:** give media and contacts independent queues/budgets, reserve a real minimum turn for each, and checkpoint portrait traversal.

### F09 — Resume can exit before clearing a saved pause

The paused notification's Resume action always starts `FaceScanService.GROUP`. `FaceScanService.kt:52` returns immediately when `FaceJobs.state.busy`, before `AutoPeople.resume()` at `:54`.

A paused notification can already be visible while a background job is finishing an in-flight operation. A Resume tap in that interval can exit without clearing the 24-hour pause. The People status panel disables some pausing interactions, but the notification route does not share that protection. E06 verifies the service branch ordering; the notification interaction still needs Android instrumentation.

**Fix direction:** persist the resume intent before admission checks and queue one continuation if a worker is draining. Ensure the foreground-service lifecycle is handled correctly for queued/duplicate commands.

### F10 — Face commits and grouping dirty state have a crash window

Face results/signatures commit to SQLite, then `FaceStore.changed()` advances a SharedPreferences revision through `AutoPeople.dirty()` and `apply()`. The grouping checkpoint also lives outside the SQLite transaction. A crash between the committed face/vector change and durable preference persistence can leave matching work unmarked.

`PeopleRevision.kt` already provides transactional UI revisions, but grouping does not use a transactional work/evidence marker. The existence of the window is source-confirmed; lost-work frequency has not been tested with process-kill injection.

**Fix direction:** keep grouping/refinement work revisions or queue rows in SQLite and update them atomically with the corresponding face writes.

### F11 — Full People cache still depends on video access

`PeopleActivity.kt:192` and `:210` require `MediaAccess.cacheable()` for reading/writing the full snapshot. That predicate rejects full photo access + selected-media permission + no full video access. The full snapshot scope also uses the combined `PeopleData.access()` string.

The compact launch cache was separated from video permission in earlier work, so the initial list may appear while detailed data still rebuilds after process restart. Changing video access can invalidate data that only needs photos.

**Fix direction:** give the full People snapshot a photo/contact/recognition evidence scope; continue revalidating selected-only photo access. Test cold reopening a person folder under mixed photo/video permissions.

### F12 — Pair comparison reuse is too broadly invalidated

`GroupRules.agreedPair()` enumerates group pairs. `PeopleGrouping.resumable()` creates a new comparison cache when the whole generation changes, including a changed group's photo set or references. Comparisons between other unchanged groups can be discarded too. `IdentityComparisonCache.slot()` additionally stops caching negative pairs above 5,000 groups.

This is a scalability weakness, not a claim of a measured stall on the reported phone. At 5,000 groups there are 12,497,500 unordered pairs before reference-vector costs. Bounded prototypes reduce each pair's cost but do not remove the quadratic search.

**Fix direction:** retain pair evidence per reference/version, invalidate affected groups only, and build an indexed candidate set. Keep all existing conflict and manual-correction safeguards.

### F13 — Background matching does not expose its available counts

`AutoPeople.kt:144` supplies grouping's phase callback but not its progress callback. The notification receives a zero/unknown total for Matching faces even though `PeopleGrouping.run()` has `pending.size` and completed counts. The background rate estimator also starts over each short job.

Indeterminate progress is appropriate for truly unknown work, but available face-matching counts should not be discarded. A single total ETA is still uncertain because Android controls scheduling gaps.

**Fix direction:** report separate photo/face/group stages, persist useful counters across batches, and label active-work estimates separately from scheduling delay.

### F14 — Shared detector contention is recorded as a photo failure

`FaceEngine.detectBitmap()` uses a shared semaphore and immediately throws when the previous native task is still finishing. A timeout deliberately keeps that semaphore held until native completion. `FaceProcessing.run()` treats the resulting shared-resource exception like an image failure and saves it against the next photo's retry budget.

If a native task is stuck or slow after timeout, unrelated valid photos can accumulate failed attempts without actually being detected. The path is visible in source, but no native hang was induced in this audit.

**Fix direction:** classify detector-busy/timeout as run-level transient conditions, back off the worker and preserve individual photo attempt budgets until inference was actually attempted.

## Already addressed in the described rc19 patch

Do not count these again as new findings: (1) a new zero-face detection dirtying grouping; (2) unconditional foreground grouping despite unchanged evidence; (3) `pendingSignatures()` repeatedly querying the full detection queue once per candidate. Their rc18 implementations remain in this restored workspace, but the previous turn described fixes to all three. The missing rc19 artifact prevents independent confirmation here.

## Validation and remaining uncertainty

The read-only evidence script ran **seven checks** successfully. They demonstrate the reported defects/conditions, not that a fixed app passes. E01 uses actual production SQLite SQL; E02 combines the actual retry query with a completion-branch model. E03/E04/E07 are explicitly bounded-work, operation-count or scheduling models. E05/E06 are source-backed dependency/branch checks. No handset latency, native model accuracy or Android lifecycle behavior was measured.

Evidence: `audit/recognition-audit-evidence.json`. Reproduce with `python3 audit/recognition_audit_repros.py`. It records hashes of 38 core/related source files so later fixes can be compared against this exact audit. UI/helper call sites were inspected for locks, side effects, cancellation and scheduling in addition to full-body review of the core pipeline.

Existing tests protect useful behavior: completed inference reuse, cancellation boundaries, explicit corrections/conflicts, pause persistence and basic grouping. Missing coverage includes interrupted repair progression, no-change graph-work counts, provider-under-lock latency, inaccessible retry suspension, resume while draining, transactional crash recovery and foreground/background error parity. A representative real-photo holdout set is also needed to assess missed faces, false matches, difficult poses and contact naming; synthetic vector tests cannot establish real-world accuracy.

## Recommended repair order

1. Give repairs/refinement durable progress and stop whole-library rebuilds on no-change batches (F01/F02), then narrow writer lock scope (F03).
2. Unify work states and errors across foreground/background: accessible retry queue, truthful completion, persisted resume intent and transactional dirty state (F04–F06/F09/F10/F14).
3. Correct comparison dependencies, contact/media budgeting and full People cache scope (F07/F08/F11).
4. Add selective pair invalidation/indexed candidates and stage progress (F12/F13), then run large-library/device and accuracy acceptance tests.

No new APK, source release, GitHub push or production-code fix is claimed by this audit.
