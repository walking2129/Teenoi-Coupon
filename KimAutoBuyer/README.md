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
6. Read the user's real Passport number from the encrypted field entered in Kim Auto Buyer, then fill Passport number, Date of birth (21/11/1986), and Referrer code (WIEX2PIW).
7. Tap Add address, fill Address 888 and Zip code 20000, select District Mueang Chon Buri and Sub-district Bang Pla Soi, tap Save, then tap Apply.

## Device setup
Install the APK, open it, enable its Accessibility service in Android Settings, return to the app, save the login details, and press Start. Keep Makro PRO in the foreground during automated taps. Android exposes an actionable accessibility tree for the foreground window; changing to another app pauses actionable control until Makro PRO is foreground again.

## Important implementation notes
- Login details and the user's real passport number are encrypted at rest in Android Keystore-backed preferences; they are not embedded in source or APK.
- Accessibility service uses visible UI labels and editable/clickable nodes, with gesture and scrolling fallbacks. It fills only the real passport number entered locally by the user. Dynamic address dropdowns or date controls in Makro may still differ by app version and need device verification.
- Payment, OTP, identity verification, and any security challenge remain user-controlled.
- The project is an initial functional automation implementation, not yet device-verified against every Makro PRO release.
