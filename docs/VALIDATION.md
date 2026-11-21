# Verified results — 2026-10-31

## Build

- Debug APK built successfully with Android Gradle Plugin 8.11.1, Gradle 8.13, JDK 17, SDK platform 36 and build tools 35.0.0.
- R8-optimized unsigned release APK also builds successfully (3,158,822 bytes). The delivered personal-test APK is the signed debug build that ran the emulator suite.
- Android lint: **0 errors, 11 warnings**. Remaining warnings concern the deliberate portrait-only phone UI, conservative storage-space checks and optional Kotlin convenience APIs. Compiler deprecation notices concern physical-camera result access retained for API 29 compatibility and two navigation icons.
- Debug APK signature verification succeeds (APK Signature Scheme v2).

## Automated results

- **21 JVM tests passed**, 0 failed, 0 skipped.
- **10 Android instrumentation tests passed**, 0 failed, on the API 35 x86_64 emulator at 1080 × 2400; final run took 30.879 seconds.
- The device suite includes an actual Camera2 JPEG capture with exposure/WB locking, saved project metadata, subsequent decoding, and preview rendering.
- Rendering tests cover known two-color blends, soft mask hide/restore, crop dimensions, 24 layers, and six masked layers from a 12-megapixel source under the actual emulator heap budget.
- UI tests exercise project creation, account navigation, demo rendering, and both finger-drag and finger-tap mask gestures.
- Authentication tests cover PKCE/state/registration handling and foreground gating; credential tests verify encrypted-at-rest storage using synthetic tokens. No real user tokens were accessed for testing.
- Screenshots of home, account, editor, camera and captured-image views were reviewed. Visual review caught and corrected a preview double-rotation that the first capture test did not assert. The updated preview is upright and the suite passed again.

Machine-readable local results are in `app/build/test-results/testDebugUnitTest/`; device output and screenshots are in `test-artifacts/`. Reproduce device checks with `scripts/Test-Emulator.ps1`.

## Delivered artifact

`dist/Roundlight-0.1.0-debug.apk`

- Size: 59,707,904 bytes.
- SHA-256: `B1A7AF7FE8BE73E9FAAB7240B76BC78F8D153B3A76BAC0E6E90E344251FABDED`.
- This is a development/personal-test signature, not a production store release.

## Not verified here

- Live ChatGPT sign-in, account eligibility, plan usage, model availability and authenticated vision inference. The user explicitly intends to add their account later.
- Artistic quality of model-generated opacities, transformations and masks on real in-the-round photos. The illustrated demo is synthetic and is not evidence of AI quality.
- Samsung Galaxy S26 Ultra physical-lens access, f-stop capabilities, RAW + JPEG stream combinations, DNG color fidelity, exposure consistency and sustained thermal/battery behavior. The emulator does not reproduce those camera characteristics.
- Field performance with hundreds of high-resolution frames, low-storage edge cases on physical hardware, and export through every third-party document provider.

Use [TESTING.md](TESTING.md) for the phone acceptance pass. Originals and JPEG companions are preserved; this version does not perform RAW development, dense optical-flow alignment, or automatic pixel-accurate segmentation.
