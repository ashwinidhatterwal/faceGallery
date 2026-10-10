# Contact portrait intake — build 52 / 1.0.0-rc12

The contact reader now tries the numeric contact display_photo stream before PHOTO_URI and the thumbnail fallback. A successful thumbnail read no longer prevents a display-photo attempt. Streams remain bounded to 8 MiB and decoded pictures to 1200 pixels / 1.5 megapixels, with cancellation checks.

A detected face of 32–47 pixels may be encoded for confirmation-only suggestions when sharpness, pose, five landmarks and alignment pass. Faces of at least 48 pixels retain the existing automatic eligibility. Suggestion-only references participate in competing-contact ambiguity checks but cannot be automatically assigned, even with a high similarity score. People and photo-face suggestions continue to use the shared cached vectors and 0.50 floor.

Portrait policy v4 schedules one bounded reconsideration of rejected v2/v3 portraits. Previously accepted vectors remain reusable. Unchanged v4 portraits, including rejected and suggestion-only results, remain cached; provider edits with identical image bytes reuse results. Existing names, contact links, rejected hints and gallery recognition signatures remain intact. Database version stays 11.

Diagnostics expose decoded size, quality checks, suggestion_only and automatic_naming_eligible. No new permissions or network uploads.

Physical verification still needed: confirm the Android contact provider returns its largest available image; inspect Kanchan’s new decoded dimensions or suggestion-only result; check the suggested name against the actual person. Providers can expose only small images, and a 0.50 similarity does not guarantee the identity.
