# Contributing

## Before opening a pull request

1. Build the generic ARM64 package.
2. Run `./gradlew lintDebug`.
3. Test on a physical device when changing accessibility, overlays, capture, or
   native inference.
4. Remove personal device identifiers and any captured screen content from
   logs and screenshots.
5. Do not add content filtering, silent translation fallback, or telemetry
   without an explicit design discussion.

## Code style

- Java 17-compatible source syntax.
- UTF-8, LF line endings.
- Keep native model lifetime and cancellation paths deterministic.
- Keep OCR coordinate transforms normalized by the selected capture rectangle.
- Do not use content heuristics to decide whether text is "valid"; OCR fixes
  must operate on geometry, confidence, duplication, or layout.

## Bug reports

Include:

- Android version and OEM;
- app version;
- capture mode;
- source/target languages;
- translation strength;
- steps to reproduce;
- redacted logs.

Do not include serial numbers, account tokens, private messages, or unredacted
screen content.
