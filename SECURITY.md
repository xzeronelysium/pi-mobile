# Security policy

Pi Mobile is an early-stage Android client. Please do not report vulnerabilities in public issues or discussions.

## Reporting a vulnerability

Use GitHub's **Report a vulnerability** action under the repository's Security tab to send a private report. Include the affected version or commit, Android version/device where relevant, reproduction steps, and the potential impact. Do not attach real credentials, personal files, or private conversation data; use sanitized examples.

If private vulnerability reporting is unavailable, contact the repository maintainer privately through GitHub before public disclosure.

## Scope and security notes

- Provider credentials are protected using Android Keystore-backed encryption.
- `MANAGE_EXTERNAL_STORAGE` grants broad storage access and is requested for workspace support.
- Shell commands run as the app's UID and are **not sandboxed**.
- The JavaScript package Worker has no Android bridge and only receives declared inputs/outputs, but third-party packages remain untrusted code.

The project is maintained on a best-effort basis; a response or fix timeline is not guaranteed.
