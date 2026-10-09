# Mosaic Gallery 0.9.1 — version code 40

This update fixes the 0.9.0 media upgrade. It keeps the gallery face model, database schema, saved identities, 24-hour notification pause and private signing workflow.

## Repairs

- The old startup video request incorrectly treated selected-media access as a reason to skip adding video access. The update requests photos and videos together once, including on an existing installation. Settings → Photo and video access allows an explicit grant or reselection later.
- Main gallery resume now compares the complete media permission scope, rather than only the broad Full/Partial label. Video content changes also invalidate the media index. If volume enumeration is temporarily empty, the primary collection fallback preserves its URI namespace and is marked incomplete so saved identities cannot be pruned. Video providers that reject optional metadata get a minimal-column retry; incomplete photo metadata never replaces saved face fingerprints; a denied video query does not hide accessible photos.
- Three-dot menus use compact themed panels positioned beside their actual buttons, with bounds checking and an above-button fallback when needed.
- People cache invalidation now distinguishes internal activity navigation from returning after background use. Selected-media access still revalidates on return, and permission changes invalidate immediately. Warmup does not publish a stale or incomplete gallery snapshot.
- Contact portraits prefer PHOTO_URI, then a numeric contact URI for full-size and thumbnail fallbacks. Recognisable smaller portraits use contact-specific quality checks; automatic naming retains its strict similarity and ambiguity gates. A clear match can establish an unassigned gallery face's group without inserting contact images into the gallery.
- The portrait reader version retries previously skipped contact references once, without changing the gallery model or restarting completed gallery inference. Unchanged references are then reused.
- Contact work receives an early bounded turn in each quiet batch. Retry deadlines survive completed-sweep caching. A temporarily unavailable contacts provider backs off independently and cannot prevent gallery face recognition.

## Phone acceptance

1. Install using the same release signing key. Accept the updated photos/videos request; on selected access, select a video as well. Check videos in Photos and Albums. Change permissions while away and return.
2. Open each three-dot menu in portrait and landscape; check anchoring, scrolling, dismissal and Settings navigation.
3. Switch Photos → Search → Photos → Search repeatedly with full and selected access. Profiles and cached face crops should remain available. Add/delete a photo, or change a person name, and confirm the updated result appears.
4. Allow contacts and enable automatic contact-photo matching under Settings → Recognition and contacts. With healthy battery/temperature, check a clear contact portrait against existing photos. Missing, blurred, multi-person and ambiguous portraits must remain unlinked; saved names and manual corrections must survive.

Real contact/model match accuracy, hardware video playback and manufacturer background restrictions still require device acceptance. The automated checks use controlled providers, metadata, quality inputs and signatures.
