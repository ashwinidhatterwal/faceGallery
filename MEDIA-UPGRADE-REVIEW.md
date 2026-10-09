# Face Gallery 0.9.0 media upgrade

Version code 39; model, face-database schema, signing workflow and saved identities unchanged.

## Changes

- Mixed photo/video gallery and albums; duration/MIME metadata; permission-scoped cache revision.
- Bounded thumbnails, with a play badge, including Android 9 video frame extraction.
- Media3 1.11.1 local playback in the existing horizontal pager. Only the selected page creates a player; all video players release on scrolling away or viewer pause. Audio focus and headphone disconnection are handled. No Internet permission is added.
- Themed seek/play controls, automatic first playback, pause/position preservation across viewer recreation, retry for playback errors. Video editing and video face extraction are deliberately outside this release.
- Themed menus, real Settings destination and adaptive gallery icon.
- Persisted 24-hour notification pause; reboot restoration, explicit early resume and permanent Settings pause are separate. Scheduling updates are serialized against pause/resume races.

## Phone acceptance still required

1. Browse photos and MP4/HEVC videos together on Android 9 and Android 13+; check thumbnail size, play badge and albums.
2. Try full, selected, image-only and video-only grants; revoke video permission and confirm inaccessible videos disappear.
3. Open landscape/portrait/rotated/long videos; autoplay, drag the timeline, pause, rotate the device, swipe to another item, press Home and return. Confirm no audio continues in the background.
4. Disconnect headphones and open an unsupported/damaged file. Confirm playback pauses or provides Retry without a crash.
5. Verify light/dark menu layouts and each Settings navigation.
6. Pause from the notification, close the app and restart the phone. Check that work stays paused for the remaining 24-hour period and resumes later under safe power/temperature conditions. Permanent Settings pause must not auto-resume.

Automated checks cannot establish hardware codec compatibility, frame-rate smoothness or the behavior of a manufacturer's battery restrictions.
