# Kim Auto Buyer (Android)

Android accessibility helper for the user's own Makro PRO account and card application flow.

## Current app build

- Version: 1.9 (versionCode 10)
- Android minSdk: 26
- The in-app updater checks the public GitHub Pages manifest at:
  `https://walking2129.github.io/Teenoi-Coupon/update-server/latest.json`
- The updater downloads the APK with Android DownloadManager, verifies the SHA-256 digest from the manifest, then invokes Android's Package Installer. Android still requires the user to confirm installation; this app does not silently install packages or bypass Play Protect.

## Release signing setup (required once)

The old debug APKs were built with runner-generated debug signing certificates. A permanent release key is required so Android can accept future updates as an update to the same app.

Create one **GitHub Actions repository secret** in `walking2129/Teenoi-Coupon`:

- `KIM_AUTOBUYER_SIGNING` — a JSON value containing the Base64 keystore, store password, key alias, and key password.

The keystore and passwords must remain private. Never commit the keystore, its Base64 content, passwords, or a `local.properties`/signing-properties file to the repository. After adding the secrets, run the `Build and publish Kim Auto Buyer updates` workflow from GitHub Actions (or push a change under `KimAutoBuyer/`). It builds a release-signed APK, calculates SHA-256, writes `update-server/latest.json`, and commits the APK and manifest to the Pages site. The existing Pages workflow then deploys the files.

The first installation signed with this permanent key must replace the currently installed debug-signed build. Android signature rules mean that the old debug build cannot be updated in place with a different signing certificate. Back up any details needed to re-enter before uninstalling the old build. After this one-time transition, subsequent APKs built with the same key can update the installed app in place.

## Included automation flow

1. Store one phone/email, password, and the user's real Passport number locally using AndroidX EncryptedSharedPreferences (Android Keystore-backed).
2. Open Makro PRO.
3. Detect the login form and fill phone/email.
4. Detect the password form and submit the normal password login button (it deliberately does not select Login with OTP).
5. Find Apply for Makro Card, choose Personal, then choose Passport.
6. Read the real Passport number supplied by the user in the local app field, then fill Passport number, Date of birth (21/11/1986), and Referrer code (WIEX2PIW).
7. Tap the actual Add address button, fill Address 888 and Postal code 20000, then select the first newly displayed District option and the first newly displayed Sub-district option. Save the address overlay, verify it closes, and then tap Apply.
8. Wait for Home, fill the product search field with คาราบาว without pressing Search or Enter, then set only the first two CARABAO product rows to quantities 3 and 2 using their own + controls. Each increment is checked before another click.
9. Use one Android Global Back action to return to Home, identify the top-right Cart control by its accessible label/resource ID (never by badge number alone), open it, and verify the cart screen before stopping.

## Device setup

Install the app, open it, enable its Accessibility service in Android Settings, return to the app, save the login details, and press Start. Keep Makro PRO in the foreground during automated taps. Android exposes an actionable accessibility tree for the foreground window; changing to another app pauses actionable control until Makro PRO is foreground again.

For app updates, press **ตรวจสอบและติดตั้งอัปเดต** in Kim Auto Buyer. The app may request the Android permission to install packages from this source once. The updater requests Android's supported self-update mode that may avoid a confirmation screen for eligible self-updates. Android can still require user action depending on device policy, installer state, or security checks; the app does not bypass Play Protect.

## Security and limitations

- Login details and the user's real passport number are encrypted at rest in Android Keystore-backed preferences; they are not embedded in source or APK.
- The updater only accepts HTTPS URLs from the configured GitHub Pages host and checks the downloaded APK against the SHA-256 value in the update manifest before opening the installer.
- Payment, OTP, identity verification, and any security challenge remain user-controlled.
- Date of birth is entered as the text `21/11/1986`; the automation does not open a date picker. If the field is not exposed as a text input, it stops instead of selecting a calendar date.
- Address, dropdown, product and Cart actions require a real accessible clickable control. The automation waits or stops when it cannot identify one safely; it does not infer the Cart from a numeric badge alone.
- Accessibility automation must still be verified against the current Makro PRO app version on the actual device. Dynamic layouts, accessibility labels, and dropdown presentation can change.
