# Privacy

## Summary

Floating Screen Translator does not include accounts, analytics, advertising,
telemetry, or a project-operated server. OCR and local translation are designed
to run on the device.

## Screen images and recognized text

- Screen images are captured only while the user has enabled the translator and
  granted the required capture or accessibility permission.
- Images are processed in memory for OCR and are not uploaded by this project's
  code.
- Recognized text is retained only in process memory for the latest translation
  and short context window. The project does not write a screen-text history.

## Cloud API mode

Cloud mode is opt-in. When it is selected:

- recognized text from the configured capture region is sent directly to the
  endpoint entered by the user;
- the third-party provider's retention, moderation, billing, and privacy terms
  apply;
- API requests may be subject to the provider's content policies.

The project does not operate or proxy that endpoint.

## API keys

API keys are stored locally using Android Keystore-backed AES-GCM encryption.
They are not committed to the repository and are not sent to the project
maintainers.

## Model downloads

Model files are downloaded from Google/MediaPipe or Hugging Face URLs. Those
providers receive normal HTTP request metadata such as IP address and user
agent under their own policies.

## Permissions

- `SYSTEM_ALERT_WINDOW`: display the floating panel.
- `FOREGROUND_SERVICE*`: keep capture and translation running while visible.
- `POST_NOTIFICATIONS`: show the foreground-service notification.
- `INTERNET`: download models and use optional API mode.
- Accessibility service: optional target-window capture and compatibility
  assistance.
- `WRITE_SECURE_SETTINGS`: optional developer-only recovery of this app's own
  accessibility component after the user grants it through ADB.

## Data deletion

Clearing app storage or uninstalling the app removes local settings, API keys,
and downloaded models. Models in the app-specific external directory are also
removed when the app is uninstalled unless the operating system preserves them.
