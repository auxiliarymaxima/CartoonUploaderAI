# Kids AI Video Creator (CartoonUploaderAI)

A native Android kids-video creator that uses a **public, no-account Hugging Face ZeroGPU video generator** and Android's on-device Media3 editing stack.

## What the app does

- Type a child-friendly scene prompt.
- Pick a cartoon look: 3D kids cartoon, 2D, clay, or storybook.
- Generate 2–5 second AI clips in 9:16, 16:9, or 1:1.
- Select music, narration, or another audio file from the phone.
- Loop the chosen audio under the clip and export H.264/AAC MP4.
- Save directly to `Downloads/KidsVideoCreator` using Android MediaStore.
- No storage permission and no API key are required.

## Free AI provider

The app calls the public Gradio API of `alexcheng0072/wan27-free-video-generator` on Hugging Face Spaces. That Space currently runs `FastVideo/FastWan2.2-TI2V-5B-FullAttn-Diffusers` on ZeroGPU and exposes a `generate_video` endpoint. It has its own safety filters and free-usage queue/limits.

Because this is a third-party community service, availability is not guaranteed. The Android app contains no paid API credential and no account token.

## Build

GitHub Actions builds both:

- `app-release.aab` — signed Android App Bundle
- `app-release.apk` — signed APK for direct installation/testing

The workflow creates an **ephemeral CI signing key** on each run so no private signing key is committed to this public repository. This is safe for testing and personal distribution, but it means builds from different workflow runs are not update-compatible with each other. For Play Store long-term updates, use a persistent upload key stored as GitHub Actions secrets.

## Android

- Package: `com.cartoonuploaderai`
- Min SDK: 29
- Target SDK: 35
- Kotlin / Android Views
- Media3 Transformer 1.11.0
