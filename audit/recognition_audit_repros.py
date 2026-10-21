"""Read-only SQLite reproductions and explicitly labelled code/algorithm checks.

Run from any directory. These checks expose defects; they are not Android tests
and a passing check means the reported defect/weakness was reproduced.
"""
from pathlib import Path
import hashlib
import json
import math
import random
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/mosaic/gallery"

def source(name):
    return (SRC / (name + ".kt")).read_text()

def sql_literal(text, prefix):
    return re.search(r'"(' + re.escape(prefix) + r'[^"\n]*)"', text).group(1)

def run():
    face = source("FaceStore")
    auto = source("AutoPeople")
    grouping = source("PeopleGrouping")
    store = source("PeopleStore")
    service = source("FaceScanService")
    evidence = source("IdentityEvidence")
    model = re.search(r'const val MODEL="([^"]+)"', source("FaceVectors")).group(1)
    db = sqlite3.connect(":memory:")
    db.execute(sql_literal(face, "CREATE TABLE IF NOT EXISTS operation_retries("))
    db.execute(sql_literal(face, "CREATE TABLE photos("))
    retry_sql = sql_literal(face, "SELECT MIN(next_time) FROM operation_retries")
    allowed_sql = sql_literal(face, "SELECT attempts,next_time,fingerprint,model FROM operation_retries")
    now = 1_000_000
    checks = []

    # Actual production SQL: retries for a URI outside the current photo list.
    db.execute("INSERT INTO operation_retries VALUES(?,?,?,?,?,?,?)", ("hidden", -1, "detect", "fp", model, 1, now - 1))
    wake = db.execute(retry_sql, (model,)).fetchone()[0]
    delay = max(60_000, wake - now)
    assert delay == 60_000
    checks.append(dict(id="E01", kind="production SQLite query", finding="F04", accessible_photos=0, retry_delay_ms=delay, outcome="Hidden URI still schedules retry"))

    # Actual retry query + the source's completion branch, with a future error.
    db.execute("DELETE FROM operation_retries")
    db.execute("INSERT INTO photos VALUES(?,?,?,?,?)", ("broken", "fp", "detector", "error", "decode"))
    db.execute("INSERT INTO operation_retries VALUES(?,?,?,?,?,?,?)", ("broken", -1, "detect", "fp", model, 1, now + 60_000))
    attempts, next_time, _, _ = db.execute(allowed_sql, ("broken", "-1", "detect")).fetchone()
    due = attempts < 3 and now >= next_time
    more, outcome_failure = due, False
    assert 'else if(more || outcome.isFailure)FaceNotifications.waiting' in auto
    assert 'else FaceNotifications.finished(this)' in auto
    notification = "finished" if not more and not outcome_failure else "waiting"
    assert not due and notification == "finished"
    checks.append(dict(id="E02", kind="production SQLite query plus completion-branch model", finding="F05", unresolved_errors=1, due_now=due, notification=notification, outcome="Completion can be announced while retry remains"))

    # Structural precondition + bounded-work model: no retained audit cursor.
    joins = store.split("fun repairJoins(", 1)[1].split("fun repairAssignments(", 1)[0]
    assert 'for(edge in relations().filter' in joins
    assert 'if(!keepGoing())break' in joins
    assert 'store.repairJoins(keepGoing)' in grouping
    visits = [list(range(8))[:3] for _ in range(4)]
    assert len(set(sum(visits, []))) == 3
    checks.append(dict(id="E03", kind="source-backed bounded-work model, not a phone timing test", finding="F01", visits_by_slice=visits, outcome="Unchanged prefix repeats; later audit items remain unvisited"))

    # Count exactly the nested visits required by provisional() in a dissimilar
    # singleton library. This is operation counting, not an app speed benchmark.
    assert 'groups.filter{it.id!=group.id}.none' in evidence
    assert 'IdentityEvidence.provisional' in source("SignatureRefinement")
    rng = random.Random(19)
    vectors = []
    for _ in range(256):
        v = [rng.gauss(0, 1) for _ in range(128)]
        length = math.sqrt(sum(x*x for x in v))
        vectors.append([x / length for x in v])
    comparisons = 0
    for i, v in enumerate(vectors):
        for j, w in enumerate(vectors):
            if i != j:
                comparisons += 1
                assert sum(x*y for x, y in zip(v, w)) < .60
    assert comparisons == 256 * 255
    checks.append(dict(id="E04", kind="source-backed vector-operation count", finding="F02", groups=256, comparisons_per_provisional_call=comparisons, two_calls=comparisons*2, outcome="Unchanged graph can still require all-pairs comparisons"))

    # Actual generation-key inputs omit the timing/pose evidence used by agreement.
    key_body = grouping.split('private fun resumable(', 1)[1].split('if(key!=comparisonKey', 1)[0]
    assert 'ref.member.key.hashCode()' in key_body and 'ref.vector.contentHashCode()' in key_body
    assert '.time' not in key_body and '.yaw' not in key_body
    assert 'abs(a.time-b.time)>=GroupRules.INDEPENDENT_MS' in evidence
    old_independent = abs(61_000 - 1_000) >= 60_000
    new_independent = abs(2_000 - 1_000) >= 60_000
    unchanged_key_inputs = dict(ids=[1, 2], photos=["a", "b"], leaves=[1, 2], reference_keys=["a:0", "b:0"], vectors="unchanged")
    assert old_independent and not new_independent
    checks.append(dict(id="E05", kind="source-backed cache dependency check", finding="F07", old_independent=old_independent, new_independent=new_independent, unchanged_key_inputs=unchanged_key_inputs, outcome="Evidence can change while comparison generation stays unchanged"))

    # Service admission ordering: a paused background run may still be draining.
    assert service.index('if(FaceJobs.state.busy){stopSelf();return') < service.index('if(requested!=AUTO)AutoPeople.resume(this)')
    checks.append(dict(id="E06", kind="source-backed lifecycle branch check, not Android interaction test", finding="F09", busy_background=True, resume_reaches_preference_update=False, outcome="Resume command exits before clearing paused state"))

    # The source starts its 20s budget before contact processing and caps the
    # detection deadline at start+10s, regardless of contact time already spent.
    assert auto.index('val deadline=SystemClock.elapsedRealtime()+20_000') < auto.index('val contacts=ContactRecognition.runSafe') < auto.index('val detectionDeadline=minOf(deadline-10_000')
    contact_elapsed = 12_000
    detection_deadline = min(20_000 - 10_000, contact_elapsed + 8_000)
    assert contact_elapsed >= detection_deadline
    checks.append(dict(id="E07", kind="source-backed scheduling-budget model, not measured device timing", finding="F08", contact_elapsed_ms=contact_elapsed, detection_deadline_ms=detection_deadline, outcome="Contacts can consume all detection allowance for a batch"))

    db.close()
    relevant = ["AutoPeople", "FaceStore", "FaceScanService", "FaceProcessing", "FaceEncoder", "FaceEngine", "PeopleStore", "PeopleGrouping", "IdentityEvidence", "IdentityComparisonCache", "SignatureRefinement", "ContactRecognition", "PeopleCorrections", "PeopleCalibration", "PeopleActivity", "PeopleData", "PeopleRevision", "PeopleEditGate", "IdentitySuggestions", "RecognitionProgress", "RecognitionConsent", "FaceAlignment", "FaceQuality", "FaceVectors", "PhotoImages", "PeopleLaunchCache", "FaceThumbnails", "FacePreviewCache", "PhotoPeopleSheet", "ContactPhotoSync", "ContactDiagnostics", "ContactDiagnosticsActivity", "ContactPhotoOffer", "PeopleNames", "PeopleSearch", "FaceScanActivity", "FaceReviewActivity", "SimilarFacesActivity"]
    return dict(audited_version="1.0.0-rc18 / 58", rc19_artifact_available=False,
                checks=checks, checks_completed=len(checks),
                scope_note="Read-only audit; models/structural checks are not Android end-to-end tests. Existing rc19-described fixes are excluded from additional findings.",
                source_sha256={name+".kt": hashlib.sha256((SRC/(name+".kt")).read_bytes()).hexdigest() for name in relevant})

if __name__ == "__main__":
    report = run()
    target = ROOT / "audit/recognition-audit-evidence.json"
    target.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(dict(evidence_file=str(target), checks_completed=report["checks_completed"], checks=[dict(id=c["id"], finding=c["finding"], kind=c["kind"], outcome=c["outcome"]) for c in report["checks"]]), indent=2))
