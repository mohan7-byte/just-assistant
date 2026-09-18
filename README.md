# Just Assistant

A native Java Android Gemini Live voice assistant built entirely through GitHub Actions.

## What it does

- Native Android Java app.
- Gemini Live API over the official realtime WebSocket endpoint.
- Selectable Live model name with current Live-model suggestions.
- Custom persona/system prompt.
- Raw 16 kHz PCM microphone streaming.
- Native 24 kHz PCM model-audio playback.
- Server-side voice activity detection.
- Barge-in/interruption handling.
- Session resumption across Live WebSocket resets.
- Context-window compression for long conversations.
- Foreground microphone service for minimized/background operation.
- Floating animated assistant orb.
- Double Volume Up hardware shortcut through Android Accessibility key-event filtering.
- Lock-screen activation path.
- Immediate stop path that closes the WebSocket and releases audio resources.
- Gemini API key protected locally with Android Keystore-backed AES-GCM encryption.
- GitHub Actions builds a downloadable debug APK.

## First-time device setup

1. Open the app and enter your Gemini API key.
2. Choose the model and persona.
3. Start Live once while the app is visible so Android grants microphone access and the foreground service can start.
4. Enable the app's Display over other apps permission.
5. Enable Just Assistant under Android Accessibility so the global Volume Up double-press shortcut can be received.

After setup, double-press Volume Up to start or stop the assistant.

## Build

The repository uses GitHub Actions and pins Gradle 8.13 for Android Gradle Plugin 8.13 compatibility. Every push and pull request builds app-debug.apk and uploads it as the just-assistant-debug workflow artifact.

## Important Android behavior

Android requires microphone foreground services to be started while the app is visible or through an allowed system interaction. The activation path therefore opens a tiny lock-screen-capable activity first and then starts the microphone foreground service.

The global Volume Up shortcut relies on an AccessibilityService. Android documents that this service can observe key events before they reach applications, but accessibility services are intended for accessibility use. This project is designed for direct GitHub/sideloaded use, not as a claim of Play Store policy approval.

## Important API-key note

The Gemini API key is stored using Android Keystore-backed encryption, but a client-side app still possesses the key at runtime. For a publicly distributed production app, a server-issued ephemeral-token architecture is safer.
