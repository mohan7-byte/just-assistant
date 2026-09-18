# Just Assistant

Native Android/Java starter for a Gemini Live voice assistant.

## GitHub-only build
GitHub Actions builds the Android project and uploads a debug APK artifact.

## Runtime configuration
The starter exposes:
- Gemini API key
- Live model name
- Persona/system instruction

A foreground microphone service owns the long-running session and closes the realtime socket when stopped.

## Architecture note
The Gemini Live API is a realtime WebSocket protocol. The transport/protocol layer is isolated from the Android lifecycle so model and persona configuration remain independent from the UI.

## Current status
The Android project structure and CI workflow are in place. The Live wire-protocol/audio framing still needs final integration and device testing before this should be considered a production-ready build.
