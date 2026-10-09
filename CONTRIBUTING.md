# Contributing to HonoKit for Android

Thank you for helping. Issues and pull requests are welcome in English or Arabic.

<p dir="rtl">شكرًا لمساهمتك. يمكنك الكتابة بالعربية أو بالإنجليزية.</p>

## Issues

- **A bug:** use the *Report a problem* form, or Settings › About › Report a problem in the app,
  which fills in your version and phone model. Attach a file only if it has nothing private.
- **An idea:** use the *Suggest a feature* form.
- **A security problem:** never in a public issue; see [SECURITY.md](SECURITY.md).

## Pull requests

1. Fork the repository and create a branch from `main` (for example `fix/comment-colour`).
2. Keep to one change per pull request, with a test where it can be tested.
3. Run the tests and a build (see [README › Build](README.md#build)):

   ```bash
   ./gradlew :engine:testDebugUnitTest :app:testDebugUnitTest
   ./gradlew :app:assembleDebug
   ```

   Code that uses MuPDF also has instrumented tests: `./gradlew :engine:connectedDebugAndroidTest`
   on a device or an emulator.
4. Open the pull request: what changed, why, and how you checked it. For a visible change, add
   screenshots in English and Arabic, light and dark.

## How the code is written

- Kotlin and Jetpack Compose; follow the style of the file you are in (4 spaces, no wildcard
  imports, comments that say *why*).
- **Only the `:engine` module imports MuPDF**, and no MuPDF type appears in its public functions.
- Arabic and right-to-left are first-class: every new string goes in `values/strings.xml` **and**
  `values-ar/strings.xml`; layouts mirror; text fits its button in both languages and at large
  font sizes.
- No network access beyond the existing update check, no analytics, no accounts.
- Never commit PDFs or other personal files (tests build their own documents), keystores or
  passwords.
- `*.cmd` and `*.bat` files keep CRLF line endings (`.gitattributes`).

## License

By contributing you agree that your contribution is licensed under the
[GNU AGPL 3.0](LICENSE), like the rest of HonoKit.
