# Releasing MyTVPlayer

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`). Each one is a GitHub Release with:
- `MyTVPlayer-<version>.apk`: for sideloading onto a TV box.
- `SHA256SUMS.txt`: the APK's checksum.

The app bundle Google Play wants (`MyTVPlayer-<version>.aab`) isn't published with the release: it is kept on the workflow run's page under **Artifacts** for 90 days.
- Release notes taken from this version's section of `CHANGELOG.md`.

## One-time setup: your own signing key

Android only installs an update if it is signed with the same key as the installed app. Development builds use a public key that is checked into the repo, so a real release needs a private key that only you hold.

1. **Create the key on your own computer.** You need Java's `keytool`, which comes with Android Studio or any JDK.
   ```sh
   keytool -genkeypair -v -keystore mytvplayer-release.jks -alias mytvplayer \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
   It asks for a password and your name. **Back up the `.jks` file and the password somewhere safe.** Without them you can never update the app again.
2. **Add two repository secrets** under GitHub › Settings › Secrets and variables › Actions › New repository secret:

   | Secret | Value |
   |---|---|
   | `MYTV_KEYSTORE_BASE64` | the output of `base64 -w0 mytvplayer-release.jks` (macOS: `base64 -i mytvplayer-release.jks`) |
   | `MYTV_KEYSTORE_PASSWORD` | the password you chose |

   Two more are optional, only if yours differ from the defaults: `MYTV_KEY_ALIAS` (default `mytvplayer`) and `MYTV_KEY_PASSWORD` (default: the keystore password, which is what `keytool` uses).

Until these secrets exist, releases still build, but they are signed with the public development key and marked as test pre-releases.

> **Switching keys:** the first release signed with your key won't install over a build signed with the development key. Uninstall the old build once, then install the new one. That clears the app's settings and playlists, so add them again afterwards.

## Each release

1. **Bump the version** in `app/build.gradle.kts`: `versionCode` goes up by 1, and `versionName` gets the new number (e.g. `0.3.0`).
2. **Describe the changes** in a new `## 0.3.0` section at the top of `CHANGELOG.md`, written for the people using the app.
3. **Commit and push.**
4. **Start the release**, in either of these ways:
   - GitHub › Actions › **Release** › **Run workflow**, then pick the branch. This tags the commit `v0.3.0` for you.
   - Push the tag yourself: `git tag v0.3.0 && git push origin v0.3.0`. The tag must match `versionName`.
5. After about 10 minutes, the release appears under **Releases** on the repo page.

Versions starting with `0.` are marked as pre-releases. From `1.0.0` they are full releases.

## Google Play
Upload the `.aab` from the release run's Artifacts (GitHub › Actions › the Release run) to the Play Console. Play re-signs it with its own app-signing key, so Play installs and GitHub APK installs can't update each other. Pick one channel per device.
