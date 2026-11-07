# Kim Auto Buyer (Android)

Android accessibility helper for the Makro PRO sign-in flow.

## Build
The GitHub Actions workflow `.github/workflows/kim-auto-buyer.yml` builds a debug APK on pushes and manual workflow dispatch. The output artifact is `KimAutoBuyer-debug-apk`.

## Included flow
1. Store one phone/email and password locally using AndroidX EncryptedSharedPreferences (Android Keystore-backed).
2. Open Makro PRO.
3. Detect the login form and fill phone/email.
4. Detect the password form and submit the normal password login button (it deliberately does not select Login with OTP).
5. Find Apply for Makro Card, choose Personal, then choose Passport.
6. At the Passport form, fill Date of birth as 21/11/1986 and Referrer code as WIEX2PIW when those labeled editable fields are exposed. Leave Passport number for the user to enter from their real passport.

## Device setup
Install the APK, open it, enable its Accessibility service in Android Settings, return to the app, save the login details, and press Start. Keep Makro PRO in the foreground during automated taps. Android exposes an actionable accessibility tree for the foreground window; changing to another app pauses actionable control until Makro PRO is foreground again.

## Important implementation notes
- Credentials are encrypted at rest; they are not embedded in source or APK.
- Accessibility service uses visible UI labels and editable/clickable nodes, with gesture and scrolling fallbacks. DOB and referral fields are associated with their labels and may still require manual entry if Makro exposes a custom control or different wording. Never fabricate a passport number.
- Payment, OTP, identity verification, and any security challenge remain user-controlled.
- The project is an initial functional automation implementation, not yet device-verified against every Makro PRO release.
