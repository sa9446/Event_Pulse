# Google Play App Signing — EventPulse

This guide explains how EventPulse's signing setup maps to **Google Play App Signing**,
and exactly how to prepare, upload, and verify a production build on the Play Store.

## The four signing identities (don't mix them up)

| # | Identity | Used for | Where it lives |
|---|----------|----------|----------------|
| 1 | **Git tag signature** | Approving the source commit | Maintainer's GPG key (local) |
| 2 | **GitHub APK key** | Direct-install APKs on GitHub Releases | `keystore.properties` (gitignored) **or** the committed CI keystore (demo) |
| 3 | **Play upload key** | Signing the AAB you upload to Play Console | Maintainer's private keystore (never in the repo) |
| 4 | **Play app-signing key** | Signing the device APKs Google generates | Held by Google (you never see the private key) |

> ⚠️ **Rule of thumb:** GitHub APK key and Play upload key should be *different*
> credentials. The Play app-signing key is **never** requested, exported, or used
> by you — Google holds it.

## How this repo's signing config works

`app/build.gradle.kts` resolves the release signing config in this order:

1. **`keystore.properties`** at the repo root (gitignored, production flow) — if
   present with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`, that key
   signs `assembleRelease`.
2. **`keystores/eventpulse-ci-release.keystore`** (committed, demo flow) — used
   when `keystore.properties` is absent, e.g. in GitHub Actions
   (`.github/workflows/publish-apks.yml`). Well-known password, like the Android
   debug keystore — **demo/hackathon builds only**.
3. Otherwise the release build is unsigned.

The certificate fingerprint that the in-app updater pins is
`BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties`. If you switch to a
production key (step 1), **update this fingerprint to match your production
certificate** — otherwise the app's own update check will reject your APKs.

```
# Get your key's SHA-256 fingerprint:
keytool -list -v -keystore /secure/path/to/production.jks -alias your-alias
# Then update gradle.properties:
# BITCHAT_GITHUB_RELEASE_CERT_SHA256=<fingerprint, no colons, lowercase>
```

## 1. Enroll in Play App Signing (one-time)

1. Create a Play Console developer account and app (package `com.eventpulse.mesh`).
2. Go to **Setup > App integrity** and choose an App Signing option:
   - **Let Google generate and manage your app signing key** — easiest, most secure.
   - **Export and upload a key from a Java keystore** — only if you must keep your own.
   - *Recommended:* let Google generate the key. Upload keys can be reset later;
     the app-signing key is the durable identity.

## 2. Generate your Play upload key (one-time)

```bash
export JAVA_HOME=/path/to/jdk-21
keytool -genkeypair -v \
  -keystore /secure/path/to/play-upload.jks \
  -alias eventpulse-upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=EventPulse Upload, OU=EventPulse, O=EventPulse, L=Chennai, ST=TN, C=IN"
```

Store `play-upload.jks` + its passwords in a password manager. Never commit it.

## 3. Build the release AAB

```bash
./gradlew :app:bundleRelease
# Output: app/build/outputs/bundle/release/app-release.aab
```

> The AAB must be signed with the **Play upload key** for Play to accept it.
> Note: since the CI keystore is committed, a plain `bundleRelease` with no
> `keystore.properties` is signed with the **CI keystore**, which Play will
> reject. Get an upload-key-signed AAB one of these ways:
> 1. **Recommended:** temporarily point `keystore.properties` at your upload
>    keystore, build, then remove `keystore.properties`.
> 2. Temporarily rename `keystores/` (so `hasCiSigning` is false) and build
>    unsigned, then sign with `jarsigner` using the upload key.
> 3. Use the upstream reproducible flow (`tools/reproducible-builds/
>    build-in-container.sh` + `sign-play-bundle.sh` with
>    `BITCHAT_PLAY_UPLOAD_KEYSTORE` env vars), which produces and signs
>    canonical unsigned artifacts.

### Why the fingerprint in `gradle.properties` matters to signing scripts

`tools/reproducible-builds/sign-release.sh` reads
`BITCHAT_GITHUB_RELEASE_CERT_SHA256` and **rejects any key whose certificate
fingerprint does not match** — it's a hard gate, not a warning. This repo pins
the CI keystore's fingerprint (`keystores/eventpulse-ci-release.keystore`), so:

- CI-published APKs (`.github/workflows/publish-apks.yml`) pass the gate and the
  in-app updater's cert check.
- Any other key (e.g. a personal `keystore.properties` key) will be **rejected by
  `sign-release.sh` and by the in-app updater** until you update the fingerprint
  in `gradle.properties` to that key's SHA-256.

## 4. Upload to Play Console

1. Open **Test and release > Testing > Internal testing**.
2. **Create new release** → upload `app-release.aab`.
3. Confirm Play accepts the upload signature and shows package
   `com.eventpulse.mesh`, the expected `versionCode`/`versionName`.
4. Fill the release notes, save, and start the internal rollout.
5. Test on a physical device via the opt-in link. Verify startup, upgrade from a
   previous version, and the release-critical scenarios.
6. Promote the *same* tested release to production. Never rebuild or re-upload a
   second AAB for production.

## 5. Verify the Play build

- In **Setup > App integrity**, record the **App signing key certificate** SHA-256.
  This is the identity of Play-delivered APKs.
- Download a Play-generated APK from **App bundle explorer** and verify:

```bash
apksigner verify --verbose --print-certs downloaded-from-play.apk
```

The signer SHA-256 must equal the Play Console app-signing certificate (not the
upload key, not the GitHub key). Play's device APKs will **not** be byte-identical
to your GitHub APKs — that's expected and fine.

## Version bumps (every release)

- `versionCode` in `app/build.gradle.kts` must be **greater than every build ever
  uploaded to Play**.
- `versionName` should match the release tag without the leading `v`.

## Security checklist

- [ ] `keystore.properties` and all production `.jks` files are gitignored
- [ ] Play upload keystore lives only in the password manager
- [ ] `BITCHAT_GITHUB_RELEASE_CERT_SHA256` matches the key actually publishing GitHub APKs (and any local `sign-release.sh` runs)
- [ ] Play app-signing private key never requested/exported
- [ ] Uploaded AAB digest recorded next to the release

## References

- [Google Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Play Console App bundle explorer](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Android apksigner](https://developer.android.com/tools/apksigner)
- Repo docs: [maintainer-release-guide.md](maintainer-release-guide.md), [reproducible-builds.md](reproducible-builds.md)
