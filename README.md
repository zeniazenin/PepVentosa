# LightPlay

A native Android studio for the Pep Ventosa **in-the-round** technique: capture a sequence, compose the layers, then refine and export the result. Built for testing on a Samsung Galaxy S26 Ultra; Android 10 or newer is required. The app is independent of Pep Ventosa and OpenAI.

## Install and try

Build the debug APK with `./gradlew :app:assembleDebug` (Windows: `gradlew.bat`). Install `app/build/outputs/apk/debug/app-debug.apk` on your phone. This is a personal-test build, not a store-signed release.

1. **Start a new shoot.** Choose a name. Capture photos or import a set with the Android document picker.
2. In **Capture**, open the settings icon to select a camera, JPEG resolution, and RAW + JPEG if exposed by that lens. Set manual shutter/ISO, aperture where adjustable, and white balance. Compose the subject and press **Lock**, then take photos. The crosshair and optional translucent first frame help keep position and scale consistent. Keep taking frames until you are satisfied; the app imposes no frame-count limit, though storage, processing time and AI usage grow with the sequence.
3. **Align locally** creates a subject-focused starting point without an account. In **Edit layers**, long-press the subject to reposition the alignment anchor before aligning again. A low-confidence frame retains its original placement for manual correction.
4. For AI assistance, open **Account → Continue with ChatGPT**, complete OpenAI's browser consent, and return to LightPlay. Authorize ChatGPT plan usage and load your available models. Choose an image-capable model, return to your shoot and select **Compose with AI**.
5. In the finishing room, select a layer to change opacity, hide/show it, or adjust position, scale and rotation. **Hide** and **Restore** paint soft masks with your finger. **View selected layer** helps inspect a mask. **Undo** retains the last 30 edits in the current session. Color controls and edge trim affect the finished image.
6. **Export** saves JPEG (quality 97) or PNG through Android's document picker. **Back up shoot + originals** writes a ZIP containing every JPEG, available DNG files, and the JSON edit recipe.

An **illustrated demo** creates nine original synthetic tree images locally. It exercises the actual renderer and editor; it is labeled as a demo and makes no AI request.

## Camera and image behavior

- Uses Camera2, enumerating public camera IDs and physical lenses exposed through logical cameras. Unsupported lens/stream combinations produce actionable errors. Samsung may expose fewer lenses or resolutions to third-party apps than to its own camera app.
- Aperture is only adjustable when the hardware reports multiple apertures. Fixed f-stops are displayed accurately. Exposure/WB lock and manual controls are capability-dependent. Lock again after changing settings or reopening capture.
- A shoot retains its selected camera and capture dimensions after its first capture. RAW is captured as DNG with a JPEG companion, paired against sensor timestamps and capture metadata. **The compositor works on JPEG companions; it does not develop RAW sensor data.** Originals remain available for an external RAW workflow.
- Photos are stored in `files/projects/<shoot-id>/` inside app-private storage, with an atomic `project.json`. Uninstalling removes this storage. Export a ZIP for a durable backup; this version does not restore ZIP archives in-app.
- JPEG imports are preserved byte-for-byte. Other supported input formats are normalized to a JPEG with a maximum long edge of 6000 px. EXIF orientation and mirroring are applied when decoding. For best alignment, use the same framing and aspect ratio across the set.
- Normal source-over blending starts with opacity `1 / layerNumber`: 100%, 50%, 33%, 25%, etc. The frame order is capture/import order, bottom to top. Disable or reduce a frame to exclude it. Masked transparent edges are flattened onto a warm paper background; use edge trim when desired.
- Preview rendering is capped at 1200 px. Export works from source photos and caps allocation to a conservative fraction of the app's heap. Choosing 6000 px is an upper bound, not a guarantee: the actual saved dimensions depend on source size and available memory and are reported after export. No artificial upscaling is used.

## OpenAI account connection

LightPlay adapts the user's MIT-licensed **ThisRock** authentication implementation, with attribution in `NOTICE-ThisRock.txt`. It uses the official [open-source Sign in with ChatGPT flow](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), including dynamic per-installation registration, system-browser OAuth, PKCE, state and nonce verification, signed ID-token validation, encrypted Android Keystore storage, serialized token refresh, account switching and session revocation. No shared API key, client secret, Codex credentials or private ChatGPT inference endpoint is used.

The app waits until it is foregrounded again before exchanging the browser authorization code. OAuth tokens are encrypted with AES-GCM in the no-backup directory. Sign-out attempts remote revocation and always removes the selected account's local tokens. If revocation cannot be confirmed, the app directs you to ChatGPT settings.

Models are loaded from the active account's catalog. Inference uses public `https://api.openai.com/v1/responses`, with `store: false`, `stream: true`, structured JSON outputs and terminal-completion verification. Account eligibility, plan limits and image capabilities still depend on OpenAI. See [plan-usage limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

## How AI composition works

The phone first estimates small similarity transforms around the chosen subject anchor. It preserves background parallax instead of attempting a panorama or global warp. This method is intended for consistent in-the-round framing; large viewpoint changes and textureless/occluded subjects may require manual alignment.

After your explicit **Compose with AI** action, the app sends re-encoded, metadata-free previews of all frames in batches of six, with a reference image. The model proposes bounded alignment corrections, normal layer opacities and coarse soft polygon masks for distracting elements. The phone renders the suggested composite and sends one preview for a final color/composition review. The entire validated recipe is applied as one undoable edit only after all requests finish. Cancellation, invalid output, refusals, network failures and incomplete streams leave the previous recipe in place.

AI masks are editable approximations, not guaranteed object segmentation. Aesthetic quality is subjective and depends on the shoot. No live account inference or visual-quality claim should be inferred from passing offline tests.

Only previews requested for AI composition leave the phone. RAW originals and local logs are not uploaded. `store: false` does not guarantee zero provider retention; OpenAI's account policies apply. There is no analytics SDK or background upload.

## Build and validate

Requirements: JDK 17, Android SDK platform 36, build tools 35.0.0, Gradle 8.13 (wrapper included). Dependencies are pinned to the locally available Android stack used by ThisRock.

```powershell
$env:JAVA_HOME = 'path-to-jdk-17'
$env:ANDROID_HOME = 'path-to-android-sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Run device tests on a dedicated emulator with `scripts/Test-Emulator.ps1`. They create synthetic fixtures and grant camera permission to the test app. Do not run them on a personal phone containing real LightPlay shoots or credentials. See [the S26 Ultra field checklist](docs/TESTING.md) for hardware and live-account acceptance.

**Account → Export diagnostic log** produces rotating, bounded event logs with timings, counts, device/build information and source stack locations on errors. It excludes photos, credentials, account identifiers, authorization URLs and exception messages. Network errors distinguish DNS, timeout, TLS, authorization, plan limits and unsupported capabilities; inference requests are not automatically replayed after an ambiguous failure.

## Source layout

- `camera/`: Camera2 discovery, sessions, manual controls, timestamp-paired JPEG/DNG capture.
- `imaging/`: subject-weighted registration, bounded bitmap decoding, compositing and soft masks.
- `model/`: serializable, validated project recipes and coordinate/color math.
- `data/`: atomic shoot persistence, originals/ZIP export, encrypted credentials and diagnostics.
- `auth/`, `network/`: account authorization, HTTP/SSE, AI recipe contract and review.
- `ui/`, `StudioModel.kt`: Compose screens, cancellation, edit history and serialized persistence.

Research notes and source links: [docs/RESEARCH.md](docs/RESEARCH.md).
