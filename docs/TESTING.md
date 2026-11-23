# Testing

## Local checks

```bash
./gradlew assembleDebug -PscreenTranslatorArmArch=
./gradlew lintDebug
```

## Required device scenarios

### Permissions and startup

- First launch with overlay permission denied and granted.
- MediaProjection start, cancel, and rotation.
- Notification permission denied.
- Accessibility service disabled, enabled, and rebound.

### OCR and selection

- Full-screen capture.
- Small custom region near each screen edge.
- Selection containing menus or UI labels; content must not be filtered.
- Overlay covering the source text; target-window capture should still read the
  underlying application when supported.
- Full/band OCR overlap with one-character recognition differences.

### Translation

- Fast, balanced, precise, and API modes.
- Auto source detection.
- Direction changes while a local request is running.
- Rapid strength changes.
- Network failure and invalid API credentials.

### Sakura mode

- Direction availability:
  - Japanese to Chinese: option visible.
  - Auto to Chinese: option visible.
  - Any other target: option hidden.
- Model missing, partially downloaded, wrong size, wrong SHA-256.
- Load success and failure.
- First request and repeated request with reused KV prefix.
- Cancel and switch to PRECISE while loading or generating.
- Force-stop and reopen.

### Rotation/background

- Start portrait, rotate to landscape in the app and another application.
- Start landscape, rotate to portrait.
- Retain the panel for at least 30 seconds after rotation.
- Check that status text does not continue flashing between stale states.

## Regression evidence

For bug fixes, retain a redacted log excerpt and the exact steps to reproduce.
Do not commit real account data, device identifiers, API keys, or private
screen content.
