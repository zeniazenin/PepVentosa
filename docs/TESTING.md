# Validation and S26 Ultra field checklist

Automated results are recorded in `docs/VALIDATION.md` after the build and emulator checks finish. Tests do not replace a real shoot or live sign-in.

## Automated coverage

- Equal-weight layer opacity, transforms in portrait/landscape, neutral color grading and memory-bounded export dimensions.
- Translation recovery against known synthetic geometry and rejection of textureless registration.
- Unsafe file paths, non-finite edits, invalid AI polygons and incomplete AI layer plans.
- OAuth PKCE vector, state/registration checks, duplicate callbacks, browser foreground gating and bounded DNS retries.
- Compatible Responses request fields, terminal completion, interrupted streams, refusals and late usage errors.
- On-device JPEG compositing, hide/restore masks, persistence, crop dimensions, many-layer rendering and encrypted credential storage.
- Home/account navigation, demo-to-editor workflow, and an actual emulator Camera2 capture saved into a shoot.

## First test on your Samsung Galaxy S26 Ultra

1. Install the debug APK. Start a shoot, grant camera permission and inspect each offered lens before adding photos. Check the displayed f-stop and supported resolutions. Lens availability comes from Samsung's Camera2 implementation.
2. Select the main rear lens and JPEG first. Compose a well-lit tree, select daylight WB, lock settings and take 20–30 sharp frames along a 90° arc. Keep the subject and distance consistent. Confirm both the preview and saved shots are upright and match the framing guide.
3. Reopen capture. Confirm the selected lens and dimensions are retained, re-lock settings, and add another frame. Background/reopen the app and confirm the shoot is intact.
4. Try each exposed physical lens in a separate shoot. Compare its preview and saved image. Try RAW + JPEG at a moderate resolution, verify both files are in the ZIP backup, and open a DNG in a trusted RAW editor. If a combination fails, retain the exported diagnostic log and retry JPEG at a lower size.
5. Align locally. Inspect the trunk/core in each layer; low-confidence registration should preserve the original placement. Try the position/scale/rotation sliders and undo. Check frame edges before export.
6. Paint hide and restore masks on a selected layer, including near an edge and after moving/scaling the layer. Change brush size and strength. Reopen the shoot and verify masks/opacity/color are preserved.
7. Connect your ChatGPT account in the system browser and return promptly to Roundlight. Grant plan usage separately, load models and pick one with image input. Run AI composition, inspect the result, then undo it. This is the first live check of account eligibility and visual quality.
8. Test canceled sign-in, declined plan usage, offline AI requests, cancellation during composition, and account switching. Existing photos and the pre-AI recipe should remain available. Review limits through Manage ChatGPT access & usage.
9. Export JPEG and PNG; verify actual dimensions and visual agreement with the preview, including masks and crop. Export a ZIP before uninstalling. Check exports in Samsung Gallery and another image viewer.
10. Repeat with 50–100 frames and high-resolution sources. Note render latency, app responsiveness, battery/heat and the actual exported dimensions. Export diagnostic logs for any failure or unexpected camera behavior.

## Reporting a problem

Use Account → Export diagnostic log. Include the app version, selected lens/resolution/format, what you tapped, and whether the issue repeats. Logs exclude photos and credentials. If you choose to share an image for visual debugging, use a non-sensitive example and include the original and exported composite.
