# Security policy

## Reporting

Please report vulnerabilities privately through GitHub Security Advisories for
this repository. Do not open a public issue containing exploit details, API
keys, personal screen text, or device identifiers.

## Security-sensitive surfaces

This application intentionally uses powerful Android capabilities:

- screen capture;
- accessibility services;
- overlays;
- optional network endpoints configured by the user;
- optional accessibility auto-recovery through
  `WRITE_SECURE_SETTINGS`.

Security reviews should focus on:

- accidental capture or upload of screen content;
- accessibility-service scope;
- exported components and intents;
- API endpoint validation and key storage;
- native model-file validation and memory safety;
- vendor-specific overlay recovery code.

## API keys

Never commit API keys, signing keys, `keystore.properties`, or `.env` files.
The repository ignores common secret and signing files, but contributors remain
responsible for checking changes before pushing.

## Default behavior

Cloud translation is disabled until the user explicitly configures consent and
an endpoint. Accessibility auto-recovery is disabled by default and acts only
on this application's own accessibility component.
