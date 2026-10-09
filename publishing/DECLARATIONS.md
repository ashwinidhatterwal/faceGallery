# Permission and app-content drafts

Review against the signed release and the actual questions in Play Console.

## Photos and videos: broad access

Face Gallery's core purpose is browsing and organising the user's local photo and video library. READ_MEDIA_IMAGES allows a persistent photo grid, albums, favourites and on-device face grouping across permitted photos. READ_MEDIA_VIDEO allows the same library views for local videos, thumbnails and in-gallery playback. A picker limited to individual import actions does not provide the library-wide, recurring browsing and organisation that constitute the main gallery experience. The app supports selected-media access when users choose it and remains usable without contacts access.

Use this explanation for the photo/video form. Demonstrate actual browsing of both media types. No MANAGE_EXTERNAL_STORAGE is requested. Legacy storage permissions are API-limited; Android 9 write access supports explicit media edits/deletion. Recognition is photo-only, not video face analysis.

## Foreground service: dataSync / local processing, other

The user explicitly starts a face scan, signature build or grouping operation from Recognition tools. The service processes permitted local photos to generate saved face results and people groups. It displays an ongoing notification with a pause control, saves completed work and stops when finished, paused, permission is revoked or battery/thermal limits apply. Opening the gallery and regular scheduled work do not start a foreground service.

Deferred work delays newly available groups but does not prevent gallery browsing. Interrupted work retains committed results; uncommitted items remain pending. The user can resume from Recognition tools, and scheduled jobs can later continue when allowed. It is not a network transfer, background video player or media transcoder.

Video evidence: open Settings > Recognition tools, explicitly start a scan, show the notification and its Pause for 24 hours action, reopen the scan status and show saved progress. Host a viewable recording and provide its real URL. Record using consented test photos. Do not submit a mockup.

## Other app-content fields

| Field | Proposed response / evidence |
| --- | --- |
| Ads | No; no advertising SDK included |
| App access | All features accessible without account or paid login. Grant media access and supply local media to test gallery features; contacts optional |
| Reviewer instructions | On first launch choose Enable people grouping or Browse only, then grant selected/all media access. Contacts may be declined. Settings exposes recognition controls; no external test account needed |
| Account creation/deletion | No account creation feature. Local data can be cleared in-app or Android app storage; original media is retained |
| Contact permission | Optional names/photos/lookup links for local contact selection and portrait matching; no phone-number or email columns queried for matching |
| Notifications | Explicit scan status and pause control; automatic jobs have no progress notification |
| Boot receiver | Restores scheduled jobs; does not launch a foreground service from boot |
| Accessibility / location / camera / microphone | No permissions for these features |
| Target audience / rating | Owner must select actual intended audience and answer IARC questions. Do not claim a rating in advance |
| Health / financial / government / news | No such features. Answer any presented forms based on the gallery's actual functionality |

Public support and privacy details must be final before submitting.
