# People folders and refresh — build 53 / 1.0.0-rc13

## Findings and changes

The previous People refresh handler converted every exception into a photo-permission error. That text did not establish the cause of the intermittent failure shown on the phone. Canceled reads are now ignored; permission loss clears results; other failures preserve already rendered results and retry at 1, 3 and 10 seconds, stopping after three retries. Actual exceptions are recorded in the local Android log under PeopleRead. Permission/access-scope changes and selected-media invalidation clear old views before a read. No network diagnostics are added.

People-folder loads previously computed all gallery identity capsules and contact-name hints even though the folder only needed its known memberships, labels and media. Folder reads now skip those unused calculations and contact-name provider resolution, with a separate cache to keep the People screen's suggestions intact.

Opening a group photo previously rebuilt the full PeopleSearch index before submitting photos to the pager. A bounded, in-process metadata handoff now supplies the folder list through a small random token. It expires automatically when the graph revision, media generation or permission scope changes; a missing handoff after process death follows the existing validated search path. The viewer submits the tapped photo immediately on creation, using available metadata, while a necessary refresh runs in the background. A temporary search failure no longer closes the viewer; a successful result that excludes the current photo still closes it.

People folders now use the existing PhotoGridAdapter selection overlay and GlideSelection, with share, Android-confirmed deletion, details, select all and exit selection. Photo selection is separate from face corrections. Selection/deletion state survives activity recreation. Sharing uses one shared implementation in the main grid and People folders, including mixed-media MIME handling, URI grants and the existing 200-item limit. Videos pass their MIME type and avoid still-image transitions.

Unchanged photo lists skip rebuilding date headers. The existing thumbnail cache, asynchronous image decoding, background recognition checkpoints, contact portrait policy v4 and explicit contact-suggestion rejections remain in place. Database version remains 11.

## Verification limits

Automated validation covers folder sharing and selection, permission/revision cache invalidation, retry limits, cancellation handling, immediate viewer metadata, and MIME/token handoff, alongside the full existing regression suite. This does not measure frame times on the user's phone. The exact device-specific exception behind the old generic message still requires a local PeopleRead log if it recurs. No blanket production-readiness claim is made.
