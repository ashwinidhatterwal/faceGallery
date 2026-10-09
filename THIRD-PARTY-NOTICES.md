Lucide vector icons from https://github.com/lucide-icons/lucide (retrieved 2026-10-06).
Only used icons are bundled as Android VectorDrawables. No icon font or runtime
icon library is added. Full ISC/Feather notices: app/src/main/assets/LUCIDE-LICENSE.txt.

## Phase 2 ML Kit

Bundled com.google.mlkit:face-detection:16.1.7 and its transitive runtime dependencies are included through Gradle. SDK/model terms: https://developers.google.com/ml-kit/terms and https://developers.google.com/terms . The model is third-party software, not covered by the project’s own license or the Lucide license. Google documents on-device input/output processing and possible SDK metrics; this app removes network permissions from its merged manifest. No Firebase Authentication, cloud recognition or analytics feature is configured. Distribution disclosures must reflect the final SDK configuration.

## Phase 3 encoder and runtime

- MobileFaceNet weights from [foamliu/MobileFaceNet](https://github.com/foamliu/MobileFaceNet),
  Apache 2.0; supplied through the [Qualcomm MobileFaceNet model card](https://huggingface.co/qualcomm/MobileFaceNet)
  (Apache-2.0 metadata), TFLite float export v0.63.0. MOSAIC removed the second-face branch,
  preserving the trained weights and one-face original/flip sum. License and changed-file
  notice are shipped as app assets. Source provenance/checksums are in PHASE3.md and tools/.
- Google AI Edge LiteRT 1.4.1, Apache 2.0. Full runtime license shipped as an asset.
- No other face-recognition weights (including research-only InsightFace packs) are bundled.

## Local video playback

AndroidX Media3 ExoPlayer and UI 1.11.1, Apache 2.0, from https://github.com/androidx/media . The full Apache 2.0 license is included in app/src/main/assets/LITERT-LICENSE.txt. Playback uses local media URIs; network permissions remain removed. The new launcher artwork is native vector artwork created for Face Gallery.
