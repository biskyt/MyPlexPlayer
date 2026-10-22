# CLAUDE.md: working on MyTVPlayer

MyTVPlayer is an Android TV IPTV player that also runs on phones and tablets by touch. It's written in Kotlin with Compose for TV, Media3/ExoPlayer and Room.
- Modules: `app` (Android) and `core` (pure Kotlin: parsers, key mapping, Xtream client).
- The repo is `biskyt/MyTVPlayer` on GitHub. It moved from `biskyt/tvapp`, which still redirects.

See also:
- `RELEASING.md`: signing and how releases work.
- `ROADMAP.md`: what's planned.
- `CHANGELOG.md`: what changed in each version.

## Standing instructions from the owner
- **Run `/simplify` and `/code-review` before every commit.**
  - Apply their findings, or note why one is skipped.
  - Never commit first and review afterwards.
- **No test APKs unless asked.** CI builds no APK on pushes; APKs are built only for release tags. Build a debug APK
  only when the owner asks for one.
- **Steer clear of any reference to, or hint of, piracy.** This applies to code, docs, store text, changelog and commit messages.
- **Keep overhead and risk low.** Prefer small, well-tested changes, and don't widen a release on your own initiative.
- **Flaky tests must be root-caused and fixed.**
  - Don't re-run CI hoping for green.
  - Never skip, disable or quarantine a test.
  - Don't just raise timeouts.
- **Long waits (CI, test loops, agents) go in the background**, so the conversation isn't blocked.
- **When the owner says "don't release until X", don't merge or release** until they confirm.

## Process for each change or release
1. Make the change with tests. Check that a new test fails without the fix.
2. Run the CI checks locally:
   - `./gradlew ktlintCheck detekt lintDebug`
   - `./gradlew :core:test :app:testDebugUnitTest`
   - `./gradlew ktlintFormat` fixes most style issues. Rerun `ktlintCheck` afterwards, because the format task itself exits non-zero when it changed something.
3. Run `/simplify`, then `/code-review`, then apply their fixes.
4. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
5. Add a `## x.y.z` section at the top of `CHANGELOG.md`, written for people using the app. It becomes the release notes.
6. Commit with the session's attribution trailers, then push to the working branch and open a PR to `main`.
7. When CI is green on both the push and PR runs, merge the PR (merge commit).
8. Releases are built only for a tag, never for a plain merge to `main`. When the owner wants one, push the tag
   `v<versionName>` on `main` (`git tag v1.0.2 && git push origin v1.0.2`); `release.yml` runs on `v*` tags. It re-tests,
   signs and publishes the GitHub release with the APK and SHA256SUMS.
   - A change with no app changes (CI, tests, docs) is merged without a version bump, changelog entry or release.
   - The signed Play bundle (`.aab`) goes to Play's closed testing track, and is a run artifact called
     `MyTVPlayer-<version>-play-bundle`, kept 90 days.
   - Versions `0.x` (or dev-key signed builds) are pre-releases. `1.x` and later are full releases.
9. Confirm the release exists, then give the owner the release link and the `.aab` artifact link.

## Gotchas learned the hard way
- **Android regex is ICU, not Java.** A bare `}` in a regex passes on the JVM (where Robolectric runs) but crashes on devices. That crashed M3U setup in 0.9.5. `core/src/test/.../RegexPortabilityTest.kt` checks every `Regex(...)` literal. Escape braces.
- **Robolectric UI tests:**
  - **Compose rules:** use `createUiComposeRule()` / `createUiComposeRule<Activity>()` from `testutil/ComposeRules.kt`, not `createComposeRule()`.
    - The plain rule runs effects on an unconfined dispatcher. Room flows collected with `collectAsState` then resume on Room's `arch_disk_io` threads and race the main thread's recomposition, so updates get lost now and then.
    - That was the cause of the long-running Catch-up tab / category flakes. The real app is unaffected.
  - Clear each test's `Navigator` (its ViewModelStores) at teardown with the `Navigators` rule
    (`@get:Rule(order = Navigators.ORDER)`, in testutil/Navigators.kt): otherwise ViewModels and ExoPlayers from earlier
    tests keep running into later ones.
  - `TestApp.onTerminate` runs the main looper down before Robolectric resets it: Compose's `AndroidUiDispatcher.Main`
    is a process-wide lazy, and if Robolectric drops its posted run it never posts again, so every later test's paged
    lists (`collectAsLazyPagingItems`) stay empty. That made failures depend on which test ran before.
  - `createUiComposeRule` reads kotlinx-coroutines-test's private `dispatchEvents` by reflection (no public hook in
    1.10.2; an IdlingResource isn't consulted by performScrollToNode, which then loops until out of memory). A
    coroutines upgrade that renames it fails every UI test at once.
  - **Waits:** go through `waitAdvancingClock(stepMs)` in `testutil/UiWait.kt`, because the main looper is paused. Use step 0 to run due work, and a step greater than 0 when delayed work (debounces, timeouts) must happen.
  - **Settings outlive a test:** the settings DataStore is process-wide, so set what a test relies on in its `setUp`.
  - **A panel is its own window:** `SidePanel` dialogs are separate windows. Send keys to the right root (`onAllNodes(isRoot())`).
  - **Thread dumps on timeout:** `otherThreads()` in UiWait.kt dumps the other threads when a wait times out.
- **Detekt limits:**
  - `AppUiTest` is near `LargeClass` (600 lines), so put new UI tests in their own class.
  - Composables near the complexity limit: `CategoryColumn`, `GuideGrid`, `AppRoot`.
- **Touch:** tv-material `Surface` only answers D-pad OK. Taps are wired through `LocalTouch` and `Modifier.onTap` / `onPlainTap` / `onAnyTouch` in `ui/common/Touch.kt`.
- **Never run `pkill -f <pattern>`** where the pattern also appears in your own command line, because it kills your own shell. Kill background loops by PID.
- **Network from the sandbox and from GitHub runners:** archive.org isn't reachable from either, so the app can't be tested against it from here. Wikimedia is reachable.
- **Android TV stand-in pickers:** some TVs (e.g. a Philips 48OLED806 on Android TV 10) ship stand-in "pickers" that only say "no application can handle this request".
  - The backup screen opens a picker directly only if it's Android's own (`…documentsui`). Otherwise it offers the installed file managers.
  - MyTVPlayer also accepts backup files via Open with / Share (JSON).
- **User-Agent:** every stream and download sends `MyTVPlayer/<version> (+https://github.com/biskyt/MyTVPlayer)`. Wikimedia requires an identifying UA. A per-entry `http-user-agent` in an M3U overrides it for live channels.
  - Films and episodes from M3U feeds don't store a per-entry UA yet. That was offered and isn't done.

## The owner's setup and the free playlist
- **Owner's devices:** they test on a Philips 48OLED806 TV (Android TV 10) and an Android phone. The phone needs the touch UI.
- **The free channels, films and series** come from the owner's own playlist project, https://biskyt.github.io/legalFreeIPTVplaylist/, with its own EPG at `epg.xml.gz`.
  - Films are hosted on archive.org and upload.wikimedia.org.
  - Its group titles carry prefixes ("Movies: Open Movies"). The app doesn't add them.

## Open items (as of 1.0.1, released)
- **Google Play:** each release tag uploads the `.aab` to closed testing (`alpha`); set-up is in RELEASING.md.
  Production is promoted by hand in Play Console.
- **Done:** emulator smoke tests on CI for pull requests (`.github/workflows/emulator.yml`: Android TV 9 and Google TV
  16, free on GitHub's standard runners for a public repo). Tests live in `app/src/androidTest`; the debug build adds an
  x86/x86_64 ABI only with `-PemulatorAbi=<arch>`. `.github/scripts/start-emulator.sh` boots the emulator itself:
  Google TV 16 drops off adb for a second or two after boot, which the usual runner action doesn't wait out.
- **Optional:** per-entry user agent / referrer for M3U films and episodes.
