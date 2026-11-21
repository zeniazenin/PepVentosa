# Research and implementation decisions

Reviewed 2026-10-31. The supplied video was studied using its full exported English transcript (automatic captions), with special attention to the capture and editing sections. No claim is made that an image model literally watched the video.

## Supplied tutorial

[How to Shoot the Pep Ventosa Technique (Step-by-Step Guide), The Global Photography Community](https://www.youtube.com/watch?v=eunJv_DtKyI)

- 7:16–7:50 and 14:36–15:36: maintain subject location and scale while moving around it; a partial arc can work. This informs the crosshair, first-frame overlay and subject anchor.
- 10:33–13:59: consistent exposure and white balance, sharp individual exposures and ongoing focus checks. The phone exposes only hardware-supported controls; DSLR f/5.6–f/8 advice is not simulated on a fixed-aperture phone lens.
- 16:00–17:25: begin with a manageable set, evaluate frame quality and apply a consistent base treatment.
- 17:55–19:35: align the central subject and vary layer opacity; reciprocal opacity gives equal contributions. Background disagreement is part of the intended effect.
- 20:03–21:15: use soft masks selectively and finish with restrained color and contrast. These become non-destructive layer masks and global finish controls.
- 21:18–23:38: more frames are not automatically better; the subject can remain recognizable. AI directions emphasize useful variation and restraint.

## Other primary references

- [Pep Ventosa's portfolio](https://www.pepventosa.com/): Trees In The Round, Carousels In The Round, and The Collective Snapshot identify the artistic context. No artwork from the portfolio is bundled in the app.
- [Alan Brown, advanced in-the-round workflow](https://www.alanbrownphotography.com/pep-ventosa-guide-advanced) and [his mobile experiment](https://www.alanbrownphotography.com/blog/pep-goes-mobile): useful practitioner context for mobile capture and individual choices during layering.
- [Android Camera2 multi-camera documentation](https://developer.android.com/media/camera/camera2/multi-camera): logical/physical camera relationships and non-guaranteed stream combinations.
- [Android CaptureRequest reference](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest): manual sensor values, AE/AWB locks and capture request controls.
- [OpenAI sign-in quickstart](https://developers.openai.com/siwc/quickstart), [registration](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), and [limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations): the official open-source account/plan-usage route used by ThisRock and adapted here.
- [OpenAI image inputs](https://developers.openai.com/api/docs/guides/images-vision) and [structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs): vision requests and constrained edit-plan responses.

## Scope boundaries

This implementation performs similarity alignment (translation, scale, rotation), not dense optical flow or perspective reconstruction. It does not force background consistency across viewpoints. Vision suggestions provide art direction and editable approximate masks; local rendering controls the final pixels. RAW capture preserves negatives but does not add a RAW-development engine. The native camera's actual S26 Ultra capability report, authenticated sign-in, model availability and artistic quality require real-device field testing.
