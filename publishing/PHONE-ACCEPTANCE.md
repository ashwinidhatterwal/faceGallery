# Physical release acceptance

Use the Play-installed signed release. Record device/Android version, build code, result and any issue for each item. Do not use private contacts/family photos in public evidence without permission.

- Fresh install: explanation appears before permissions; Enable starts permitted work; Browse only does not ask for contacts or run automatic recognition. Back/dismiss is not consent. Rotate/reopen while choosing; no stuck blank screen or duplicate requests.
- Update a real existing installation: saved people, names, manual corrections/favourites remain. Upgrade explanation gates automatic work. Accepting it preserves an existing permanent pause. Never clear results as an upgrade step.
- Deny contacts; gallery works and custom names save. Deny photos but allow videos; videos load/play and photo recognition stays inactive. Selected-media mode must not expose unselected media or erase saved identities.
- Photos/videos: mixed library, albums, thumbnails, video autoplay/seek/pause/audio, swipe away, background/reopen, unsupported codec; no audio/decoder remains after leaving. Use a longer video and an SD-card item if supported.
- Gestures: pinch grid without flicker; zoom follows fingers; twist snaps to right angles without zoom jumps; face panel tap opens the correct group's photos.
- Search: navigate away/back repeatedly; profiles/crops retain state and manual edits appear promptly. Menus anchor to the correct button in both themes and near screen edges.
- Contacts: clear single-face portrait, no portrait, blurry portrait, multiple faces, two similar contacts, changed portrait/name, revoked permission. Only clear matches should link unnamed groups; manually corrected names/links must not be overwritten or reattached.
- Background: let initial scan finish, then close/reopen without changing media. No full inference/rematching loop. Add one photo; only changed/new work should run. Test overnight closed-screen, reboot, battery saver, low battery and warm device. Android may defer jobs; force stop prevents jobs until reopen. Record completed counts before/after, not just notification text.
- Pause: explicit scan notification's 24-hour action persists through restart; permanent Settings pause remains paused after a day. Saved results remain intact.
- Clear results: pause background first, clear results, verify names/signatures/contact caches removed but originals retained. Enable again and verify deliberate rebuilding only.
- Release compatibility: Android 9 and Android 16 if available, a 16 KB device/emulator, both day/night themes, text scaling; Play pre-launch report has no unresolved crashes/ANRs/blockers.

Production sign-off is pending until these checks have real results. Automated evidence is separately recorded in `validation/play-preparation.json`.

## Build 46: suggestions and contact sync

- With a similar but uncertain contact portrait, open an unnamed face from People and from a photo's people panel. A name suggestion appears; opening/dismissing the picker must not name or merge anything.
- Confirm a contact suggestion. Check its name/contact on the group, then reopen the app and check it persists.
- Check that a manually named similar group appears too, and an already contact-linked group has only one suggestion under its saved name.
- Explicitly separate two people. Check the rejected group/contact is not suggested again for ordinary identification.
- Run Settings > Sync contact photos twice without edits: the second check should reuse signatures and comparisons. Add/change a contact portrait and sync again: only changed portrait data should be encoded.
- Pause background recognition, run manual contact sync, then confirm background recognition remains paused. Repeat with automatic contact matching off. Consent/permission are still required.
- Cancel sync or leave the screen midway, then run it again. Completed portraits should be reused; unrelated gallery work and manual corrections must remain intact.
- Check low battery, battery saver and a hot phone pause manual sync with a clear message. Automatic scheduling should remain as configured.

## Build 47: People grid labels

- Unnamed profiles with a suitable contact/manual-group match show `Suggested name?` below the face; saved names have no question mark.
- Tap a suggested profile and confirm it. The grid label should become its saved name without `?` after the update. Dismissing the picker must keep it uncertain.
- Switch away/back and search by the suggested name. The label and thumbnails should remain stable; unchanged portraits must not be encoded again.
- Change/remove a contact photo or saved name and run the applicable sync. Check suggestions update after the source evidence changes.

## Build 48: optional contact photos
- From a photo face and from a People group, select a contact without a photo and Save. Confirm the compact themed crop preview.
- Use photo asks for Write contacts only on this action; accepting saves a square face photo. Verify it in the Contacts app.
- Not now, permission denial, leaving the page and a missing/read-only contact leave the contact unchanged; the gallery name/link still saves.
- Existing contact photos never get replaced. Also test a contact gaining a photo while the prompt is open.
- Try a linked group with multiple images, limited photo access and deleted/modified images. The crop should be clear, correctly proportioned and from the selected person.
- Correcting an incorrectly grouped face must use that selected face, not another member of its old group.
- No suitable readable face means no photo prompt; no recognition rerun or scheduled contact writes.
