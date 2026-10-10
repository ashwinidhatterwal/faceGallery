# Build 54 — People reads, tags and universal search

Version: 1.0.0-rc14 / 54. Application ID remains com.mosaic.gallery. Face database remains version 11; existing faces, vectors, names, contact links and corrections are retained.

## People loading

The former display path resolved linked contacts through the Contacts provider on each read, opened an IMMEDIATE SQLite snapshot independently of background writers, and rebuilt the membership graph and prototype capsules again for name suggestions. These are verified code-level causes of repeated work and database contention. The phone screenshot does not identify the exception class, so the exact original device exception has not been independently reproduced.

Read snapshots now acquire the same fair coordinator as graph writers before starting their transaction. A waiting reader asks automatic recognition to yield at its next existing safe checkpoint. Saved progress and retry/checkpoint rules remain intact. Manual recognition is not automatically canceled. Full-access People previews remain visible across media refreshes; reduced or selected-media permissions still require a matching permission scope and media generation. Optional name-suggestion failures no longer fail the whole People grid.

Profile suggestions reuse the graph and capsules already loaded. Linked contact resolution runs in the existing warming worker once per contacts-provider revision or permission change, outside the display-critical read. A bounded local diagnostic records exception type and code locations, omitting exception messages and private photo/contact content. Export recognition report includes this evidence as `people_reads`.

## Contact suggestions

Contact suggestion floor is now 0.60, inclusive. It is shared by People, photo-face suggestions and contact diagnostics. Existing stronger automatic naming, competing-contact margins, rejection vetoes and user-confirmed names remain unchanged. Cached portrait embeddings are reused; this update does not change the portrait model or trigger a full recognition reset.

## Tags

The photo viewer includes a bottom-corner capsule with a 50%-opacity background. Tapping it animates the photo upward and opens an embedded themed drawer. The drawer adjusts above the keyboard and closes the keyboard on dismissal. Add a new tag, choose previously used names, remove current tags, or save an optional place label. Save also accepts text not yet submitted with Add.

Bulk tagging is available from the selection overflow in Photos, album folders, People photo folders and universal search results. A delta updates only selected media and preserves their other tags. Canceled deletion preserves tags; confirmed deletion clears assignments and place labels. Previously used tag names remain available as suggestions.

Tags are stored in separate private metadata and have their own revision. Editing them does not invalidate face signatures, change people identities or schedule recognition. Tag albums contain accessible media only, use distinct stable keys, and are assembled in one pass over media assignments.

## Universal search

The top search icon in Photos and Albums opens Search photos. Search combines confirmed people names, tag names, dates/months/years, clock times, filenames, folders and optional place labels saved in Tags. Person and inclusive date-range filters remain available. Results use the gallery viewer, support videos and retain normal share/delete/bulk-tag selection actions. The main navigation labels the existing people-profile entry People; universal search also includes a People shortcut.

Location search uses place labels entered by the user. This build does not claim automatic GPS-to-city indexing or add location permissions. Search and tags run locally. The bundled and GitHub Pages policy source describe the new metadata.

## Installation and verification

Upload this source to the existing repository and use the normal signed release workflow. Install the signed build 54 with the same signing identity as the existing installation. Do not clear app data or reset recognition results.

Automated validation is recorded in `validation/tags-search-people.json`. Device acceptance remains necessary: switch People tabs while recognition is active; reopen the app; verify permission changes; type in Tags with the keyboard visible; bulk-tag from each gallery context; open tag albums; and combine name/tag/date/place search. If People still reports a retry, export the recognition report so `people_reads` identifies the actual phone failure.
