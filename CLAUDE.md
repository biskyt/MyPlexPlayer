# CLAUDE.md: working on MyPlexPlayer

MyPlexPlayer is an Android TV client for the owner's own Plex Media Server that also runs on phones and tablets by
touch. It's written in Kotlin with Compose for TV, Media3/ExoPlayer and Room.
- Modules: `app` (Android) and `core` (pure Kotlin: source clients, parsers, key mapping).
- The repo is `biskyt/MyPlexPlayer` on GitHub.
- **It started as a copy of MyTVPlayer** (`biskyt/MyTVPlayer`, an IPTV player), imported with its history at
  `caad9d7` (1.2.1). The two apps are developed separately and are expected to drift apart. A fix worth having in
  both is ported by hand (`git cherry-pick` from a fetch of MyTVPlayer usually applies, since it follows the package
  rename); never merge MyTVPlayer's `main` wholesale. Ported so far: everything up to MyTVPlayer 1.2.4 (`a54a2ad`, in 0.3.0; 1.2.3 `884939f` in 0.2.1),
  its Play upload workflow (`dc5d194`, in 0.2.1), its clearer secondary text (MyTVPlayer 1.2.6, `900d6ae`, in 0.3.3),
  the dimmed paused screen (1.2.7, `29c9c71`, in 0.3.7) and its fixes (1.2.8, `40609e4`, in 0.4.0), the episode
  list's marks (1.3.0, `b4c1bc3`, in 0.4.3) and one list of the keys panels pass on (1.3.1, `c4074e5`, in 0.4.4), held buttons not carrying on into a panel
  (1.3.2, `73c1631`, in 0.5.0), the player not closing when shown before it's loaded (1.3.3, `1387654`, in 0.5.1), its tests' NIO warm-up
  (`8d20dcc`, in 0.5.1), the categories type switch (1.3.4, `299fc0b`, in 0.6.1),
  the TV guide as one focus stop with its own highlight (1.4.1 `4815775` and 1.5.0 `2773574`, in 0.11.0; catch-up
  left out), and the player's clock, time left and Options to More (1.7.0, `6279932`, in 0.14.0) and its fixes (1.7.1, `44805ab`, in 0.14.1), the description in the player's own sheet (1.8.0, `3a43c5d`, in
  0.15.0, in place of 0.13.0's own panel) and Back hiding the controls, with no sleep timer (1.8.1, `ba6b051`, in 0.15.0), and the next episode's card on
  Carry on watching (1.8.2, `099c88b`, by hand in 0.16.0: the next one's row isn't reported to Plex), the menu bar on film and
  series pages (1.8.3, `c456681`, in 0.16.1), and on every screen but playback and set-up (1.8.4, `1b8f228`, in
  0.16.2; its `45f431f` fixes a test MyPlexPlayer hasn't; a title's art also goes behind the bar here, which
  MyTVPlayer 1.8.5 copied), 1.8.5's opened backup file and `Navigator.open` KDoc (`3016f93`, by hand in 0.16.3), and the play button in the
  middle of the player's controls (1.8.6, `fee0749`, in 0.16.4). In 0.17.0, by hand: from 1.8.9 (`c18cb42`), the
  empty file error and the address on the error screen (a Plex token hidden), and only the buffer line of its Stream
  info (MyPlexPlayer's Playback info shows the rest), not its http/https buttons (no address to type here); from
  1.8.10 (`ecf5468`), Up and Down leaving a text box (`9456942`) and the focus back on a search result (`a17787f`),
  not its category line (a Plex title is in one library). In 0.17.1: 1.8.11 (`66ed289`, the server named once and
  Back from Home leaving the recent apps). The plain-english skill (`7de1099`) is copied as it
  is. 1.8.7 (`fc738a8`) and 1.8.8 (`9cb696c`) change the XMLTV guide's reading and channel matching, which
  MyPlexPlayer removed in 0.6.0: they don't apply.
- **Where a change is made first** (the owner, 2026-10-25): a feature or fix both apps need (the player, remote keys,
  menus, the guide's screens) is made in MyTVPlayer first, then cherry-picked here, so the shared code stays the same
  and ports apply cleanly. Plex-only work (sign-in, servers, play state, Live TV, DVR) is made here only.
  - Home's rows, the menu's order and search results' order differ from MyTVPlayer on purpose (Live TV last, 0.5.0):
    a port touching HomeScreen, TopBar's `Tab` or SearchScreen's results expects conflicts there.
  - The TV guide goes 8 days ahead here (`GUIDE_DAYS` in `data/PlaylistRepository.kt`, 0.17.3: as far as Plex's own
    guide; its sync reads it too), 3 in MyTVPlayer: a port touching GuideGrid's `nextWindow` expects a conflict there.
- **The move to Plex is under way:** the plan, step by step, is in `ROADMAP.md`. M3U and the free playlist went in
  0.2.2, Catch-up in 0.4.2 (DVR replaces it), and Xtream, added guides and guide matching in 0.6.0: every source is a
  Plex server. The database still has a few things named for playlists and providers (`playlist`, `providerEpgId`,
  `channel_epg_mapping`), kept to leave the schema and MyTVPlayer's shared code as they are.

See also:
- `RELEASING.md`: signing and how releases work.
- `ROADMAP.md`: what's planned.
- `CHANGELOG.md`: what changed in each version.

## Standing instructions from the owner
- **Write in plain English.** Use the `plain-english` skill (`.claude/skills/plain-english/SKILL.md`) for all
  documentation, code comments that explain behaviour, commit messages, PR descriptions and release notes.
- **Run `/simplify` and `/code-review` before every commit.**
  - Apply their findings, or note why one is skipped.
  - Never commit first and review afterwards.
  - Spikes (`spikes/`) don't need them (the owner, 2026-10-30: only the main app is reviewed).
- **No test APKs unless asked.** CI builds no APK on pushes; APKs are built only for release tags. Build a debug APK
  only when the owner asks for one.
- **Steer clear of any reference to, or hint of, piracy.** This applies to code, docs, store text, changelog and commit messages.
- **Keep overhead and risk low.** Prefer small, well-tested changes, and don't widen a release on your own initiative.
- **Flaky tests must be root-caused and fixed.**
  - Don't re-run CI hoping for green.
  - Never skip, disable or quarantine a test.
  - Don't just raise timeouts.
- **Long waits (CI, test loops, agents) go in the background**, so the conversation isn't blocked.
- **Answer the owner's questions straight away** (the owner, 2026-10-25): when they ask something, pause the work,
  answer first, then carry on. Leaving a question waiting behind other work reads as ignoring them.
- **Release every merged app change** (the owner, 2026-10-25: "always build until I say otherwise"): once an app PR
  is merged, start `release.yml` (step 8) and give the owner the release link.
  - A large feature that comes in steps is released once, when all its steps are merged (the owner, 2026-10-31,
    for DVR). It has one version number: the first step's PR bumps the version, and each later step adds to that
    version's changelog section, with no new number.
- **When the owner says "don't release until X", don't merge or release** until they confirm.
- **Only the app's own text is translated**: see **Languages** below.
- **Release notes are English only**, on GitHub and in Play's "What's new" (translated Play notes: not worth the work).
- **Release notes are short** (the owner, 2026-10-27): a bullet list, one sentence per change, no bold lead-ins or
  explanations.

## Process for each change or release
1. Make the change with tests. Check that a new test fails without the fix.
2. Run the CI checks locally:
   - `./gradlew ktlintCheck detekt lintDebug`
   - `./gradlew :core:test :app:testDebugUnitTest`
   - `./gradlew ktlintFormat` fixes most style issues. Rerun `ktlintCheck` afterwards, because the format task itself exits non-zero when it changed something.
3. Run `/simplify`, then `/code-review`, then apply their fixes.
4. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, for every PR that changes the app, released or
   not (except a later step of a feature released once, see the standing instructions): the patch number for fixes and small changes, the minor for a notable feature (1.1.0: languages).
5. Add a `## x.y.z` section at the top of `CHANGELOG.md`, written for people using the app.
   - A release's notes are its section plus those of any unreleased versions since the last release
     (`.github/scripts/release-notes.sh`). For Play's "What's new" (500 characters), `play-notes.py` keeps the whole
     bullets that fit in about 420 and adds a link to the full notes, so put the change users notice most first.
   - Fix a published GitHub release's notes with the **Release notes** workflow (`release-notes.yml`, input `tag`),
     which rewrites them from CHANGELOG.md without rebuilding. It doesn't touch Play: change Play's notes in Play
     Console.
6. Commit with the session's attribution trailers, then push to the working branch and open a PR to `main`.
7. When CI and the emulator runs (if the PR has them; see below) are green, merge the PR (merge commit).
   - CI runs once per commit: on pull requests and on `main` after a merge (or by hand). A push to a branch without a
     PR runs nothing, so open the PR to get CI.
   - CI runs `:core:test :app:recordRoborazziDebug` (the unit tests, recording screenshots to `app/screenshots/`; don't
     commit those unless meant to). The emulator runs are on PRs (or started by hand), and skipped when every
     changed file is one that can't reach the app: `.md`, `docs/`, `store/`, JVM tests and screenshots, or the other
     workflows and their scripts (the list is `paths-ignore` in `emulator.yml`).
8. Releases are built only for a tag, never for a plain merge to `main`. For now the owner wants one after every
   merged app change (see the standing instructions): start `release.yml`
   for `v<versionName>` on `main`. It re-tests, signs and publishes the GitHub release with the APK and SHA256SUMS.
   - **From a Claude session, run the workflow by hand:** tag pushes from a cloud session have failed with HTTP 403
     (with either repo name as the remote), while pushes to the working branch work. Use the GitHub MCP tool
     `actions_run_trigger` with method `run_workflow`, `workflow_id` `release.yml` and ref `main`. It tags
     `v<versionName>` itself.
   - **Check first** that the version bump is merged to `main`. The workflow itself refuses a run from another branch,
     or one for a version that already has a release, or (started by hand) a tag: a tag left by a failed run must be
     deleted first.
   - From a normal checkout, pushing the tag also works: `git tag v1.0.2 && git push origin v1.0.2`.
   - A change with no app changes (CI, tests, docs) is merged without a version bump, changelog entry or release.
   - The signed Play bundle (`.aab`) goes to Play's closed testing track, and is a run artifact called
     `MyPlexPlayer-<version>-play-bundle`, kept 90 days.
   - If only the Play upload failed (the GitHub release is out), run `play-upload.yml` on `main` with input `tag`
     (e.g. `v0.2.1`): it uploads that release's bundle again. Don't re-run the release.
   - Versions `0.x` (or dev-key signed builds) are pre-releases. `1.x` and later are full releases.
9. Confirm the release exists (`get_release_by_tag`), then give the owner the release link and the `.aab` artifact link
   (`actions_list` with method `list_workflow_run_artifacts` and the release run's id). For when testers see it, see
   RELEASING.md › Google Play.

## Working from a Claude Code cloud session
- **GitHub tools:** use owner `biskyt`, repo `MyPlexPlayer`.
- **CI results:** for each PR, call `subscribe_pr_activity` (repo `MyPlexPlayer`) so its CI results wake the session.
  - Each run (CI, the emulators) reports on its own: check them all with `pull_request_read` (`get_check_runs`).
  - Keep one `send_later` check as a safety net (CI and the emulator runs take about 5 minutes). Use it alone for runs
    outside a PR (a release, about 6 minutes; release notes) or if another agent already watches the PR.
- `merge_pull_request`'s `expectedHeadSha` needs the full 40-character SHA.
- Work on the session's designated branch. Once its PR is merged, restart the branch from `origin/main` for any
  follow-up (`git checkout -B <branch> origin/main`); never stack new work on merged history.
- The local `main` can be stale: compare with `origin/main` after `git fetch origin main`.

## Languages (since 1.1.0)
- The app's languages are `AppText.languages`, with UK English the default in `values/`. Strings are split by area
  (`strings_<area>.xml`).
  - Every new string needs es, pt-BR and fr translations. If the US wording differs, it also needs entries in
    `values-en-rUS` and `values-en-rGB` (see the comment there).
  - A new language needs a `values-<qualifier>` folder, an entry in `AppText.languages` and one in
    `androidResources.localeFilters` in `app/build.gradle.kts`. Otherwise release builds drop its strings;
    `EnglishStringsTest` checks this.
- **Text the app defines** comes from string resources: `stringResource` in composables, `appString` / `appPlural`
  (AppText.kt) elsewhere.
- **Text from users, playlists and guides is shown as it is:** playlist, guide, channel and category names, titles.
- **Saved data stays language-neutral.** The app saves blank values, ids or codes and names them when shown:
  `programmeTitle`, `categoryLabel` / `.label`, `GuideError.text`, `groupLabel` (in `data/CoreText.kt`) and the episode
  and season names. Never save app words (an English "Other" or "No title" was saved before 1.1.0). `SavedTextTest`
  covers this.
- **Check that translated text fits** with the store screenshots in a language:
  `./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets -PstoreLanguage=<tag>` writes them to
  `app/build/store-assets/<tag>/`. Without `-PstoreLanguage` it rewrites the committed store listing assets
  (`store/graphics` and `store/screenshots`).

## Gotchas learned the hard way
- **Android regex is ICU, not Java.** A bare `}` in a regex passes on the JVM (where Robolectric runs) but crashes on devices. That crashed M3U setup in 0.9.5. `RegexPortabilityTest` (one in core, one in app, sharing core's `RegexPortability` test fixture) checks every `Regex(...)` literal. Escape braces.
- **Robolectric UI tests:**
  - **Compose rules:** use `createUiComposeRule()` / `createUiComposeRule<Activity>()` from `testutil/ComposeRules.kt`, not `createComposeRule()`.
    - The plain rule runs effects on an unconfined dispatcher. Room flows collected with `collectAsState` then resume on Room's `arch_disk_io` threads and race the main thread's recomposition, so updates get lost now and then.
    - That was the cause of long-running flakes (a tab or category not showing). The real app is unaffected.
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
  - **Settings start from defaults:** the settings DataStore is process-wide, so `TestApp` and `PlainApp` reset it when
    each test ends. A test sets only what differs from the defaults (in its `setUp`).
  - **A panel is its own window:** `SidePanel` dialogs are separate windows. Send keys to the right root (`onAllNodes(isRoot())`).
  - **Thread dumps on timeout:** `otherThreads()` in UiWait.kt dumps the other threads when a wait times out.
  - **Images on two threads at once abort the test JVM** (exit 134, "JniConstants … Class not found: java/nio/…"):
    Robolectric's native graphics set themselves up unsafely. `TestApp` and `PlainApp` call `lookUpNioClasses()`
    (testutil/NativeGraphics.kt) first, so a new test application class must call it too.
- **Detekt limits:**
  - `AppUiTest` is near `LargeClass` (600 lines), so put new UI tests in their own class.
  - Composables near the complexity limit: `CategoryColumn`, `GuideGrid`, `AppRoot`.
- **Performance tests assert allocations, not CPU time:** CPU time grows with other test JVMs running alongside, as
  in CI and release (see `ProgrammeKindTest`).
- **Reproducing a flaky test:** Gradle's build cache replays test results. Use `--no-build-cache` with
  `:core:cleanTest :app:cleanTestDebugUnitTest`, and run core and app tests together, as release does.
  - App tests run in a JVM per two cores (`maxParallelForks` in `app/build.gradle.kts`), so which classes ran before
    a test in its JVM can differ from run to run. Process-wide state (settings, `AndroidUiDispatcher.Main`) is per JVM.
- **Touch:** tv-material `Surface` only answers D-pad OK. Taps are wired through `LocalTouch` and `Modifier.onTap` / `onPlainTap` / `onAnyTouch` in `ui/common/Touch.kt`.
- **Never run `pkill -f <pattern>`** where the pattern also appears in your own command line, because it kills your own shell. Kill background loops by PID.
- **Network from the sandbox and from GitHub runners:** archive.org isn't reachable from either, so the app can't be tested against it from here. Wikimedia is reachable.
- **Android TV stand-in pickers:** some TVs (e.g. the owner's, on Android TV 10) ship stand-in "pickers" that only say "no application can handle this request".
  - The backup screen opens a picker directly only if it's Android's own (`…documentsui`). Otherwise it offers the installed file managers.
  - MyPlexPlayer also accepts backup files via Open with / Share (JSON).
- **Plex play state** (0.3.0, `data/PlexProgress.kt`): the `watch_progress` rows mirror the server.
  - The server wins, except over a row marked `pending` (played here, not yet reported). Pending rows are sent again
    at the start of each sync, before the library is listed. A refusal (4xx) isn't retried.
  - "Carry on watching" is the server's Continue Watching, ordered by `carryOnAt` (`updatedAt` means only "last
    watched"). Taking a title off Continue Watching keeps its resume point, so "has a point" isn't "on the row".
  - Plex times are whole seconds: two plays in the same second don't order (see `PlexServerTest`'s `nextSecond`).
  - Back in the app (`MainActivity.onStart`, 0.3.8), `SyncManager.refreshPlayState` brings what played elsewhere
    without a sync: titles played since the last look (`viewedSince`, relative seconds, so clocks needn't agree),
    those that left Continue Watching since (marked unwatched elsewhere, `lastViewedAt` is cleared, so no filter finds
    them: films by id, episodes with their series) and Continue Watching. Skipped when a sync is due.
- **Plex Live TV** (0.4.0, `data/PlexLive.kt`): channels sync with the server (`PlaylistRepository.syncChannels`), each
  with a stable id from its key (`plexChannelId`, so favourites stay) and the key in `providerEpgId`. The guide is
  saved as the server's own guide source (`EpgRepository.savePlexGuide`, url `plex:<id>`), with why it last failed,
  if it did, shown on the server's row in Settings › Playlists. A channel is tuned once (`PlexLive`), its tuner let go before the next tune and on leaving; a
  server that refuses Live TV (a friend's) just has no channels.
  - **Favourite channels are Plex's** (the owner, 0.9.0: "no point storing it locally"): read from Plex's guide
    service (`PlexAccount.favouriteChannels`) at each sync, they replace this server's favourites here, in Plex's
    order (`PlaylistRepository.syncFavourites`). Adding or removing one (`ChannelRepository.setFavourite`), or the
    end of a move (`Favourites.moveEnded`, not each step), saves the account's whole list back
    (`PlexSources.setFavouriteChannel` / `orderFavouriteChannels`): each favourite as Plex sent it (raw JSON, so
    nothing Plex added is lost), a JSON array. Changes and the sync's copy take turns (`PlexSources.withFavourites`).
    When Plex doesn't take a change, nothing changes here and `Notices` says so. The favourite table is only the copy
    screens read.
- **Skip intro and credits** (0.8.0): markers come only with `includeMarkers=1` (`PlexClient.item(…, markers = true)`,
  asked for by playback only). When a pill shows and when a skip is due is core's `skipPill` (ROADMAP's rules); an
  automatic skip happens only when playback played on into it, so seeking into an intro offers the pill instead.
  OK skips through `skipKeys` in VodPlayerScreen, kept out of the player code shared with MyTVPlayer.
- **Deleting** (0.10.0, docs/plex-api.md row 19): whether a server allows it is one server setting
  (`allowMediaDeletion` in `/`), asked once per source and again after each sync; a show's "Delete episodes after
  playing" is read with `includePreferences=1` (its `/prefs` lists nothing) and saved with a PUT to `/prefs`, once the
  remote rests on a choice (`AutoDeleteChoice`). Delete always asks first (`ItemAction.Confirmed`).
- **Changing a setting's default:** `SettingsRepository.update` (and a backup) saves every setting, so a default
  already released is saved as if chosen. A new default needs a new preference key and `@SerialName`, as credits'
  automatic skip got in 0.8.1.
- **Dolby Vision with HDR10+** (0.3.2): the owner's TV (a MediaTek chip) shows HDR10, not Dolby Vision, while a Dolby Vision
  decoder also gets HDR10+ (found with `spikes/dv-diagnostics`, kept for other TVs). So a Dolby Vision decoder gets no
  HDR10+ while the screen shows Dolby Vision (`player/DolbyVisionCodec.kt`, with `core`'s `Hdr10PlusOff`): its SEI
  NALs are retyped in place to an unused type, not cut out. Other decoders, and other screens, keep HDR10+.
- **User-Agent:** every stream and download sends `MyPlexPlayer/<version> (+https://github.com/biskyt/MyPlexPlayer)`.

## The owner's setup
- **Owner's devices:** they test on a TV running Android TV 10 and an Android phone. The phone needs the touch UI.
- **Their Plex server** has Plex Pass and a tuner (Live TV and DVR). It is on their home network and can't be reached
  from a cloud session or CI: anything that needs it (sign-in, Live TV, DVR captures) is a script the owner runs.
- **Plex API references:** [Plexopedia](https://www.plexopedia.com/plex-media-server/api/) first (the owner's
  preferred source), then Plex's official OpenAPI spec at https://developer.plex.tv/pms/. Plexopedia has no Live TV,
  DVR or EPG pages. [Plezy](https://github.com/edde746/plezy) (GPL-3.0, Plex and Jellyfin) shows working Live TV,
  DVR, sign-in and playback calls. Check every endpoint against them, and prove assumptions with a spike
  (`spikes/plex_spike.py`) before building on them; the results live in `docs/plex-api.md`.
- **A real Plex server in CI and in the sandbox:** `.github/scripts/start-plex.sh <dir>` starts an unclaimed PMS (from
  Plex's .deb; Docker Hub rate-limits) with a generated film and series in about 20 s. PMS ignores play progress in
  a title's first minute, hence the 10-minute clips. It can't do sign-in, Live TV or DVR. Its clips are made with a
  static ffmpeg from GitHub (`.github/scripts/install-ffmpeg.sh`), not apt: the runners' Ubuntu mirror has stalled
  long enough to time the job out.

## Product decisions from the owner
These are built in already (or planned, for the Plex ones). Keep them when changing the areas they cover.
- **Plex, not IPTV:** Plex sign-in (PIN, entered at plex.tv/link) and Plex servers replace Xtream and M3U playlists.
  There is no free playlist and no way to add other guides: the TV guide comes from the Plex server.
- **One account, several servers:** each server is a source in the top bar's switcher. Plex Home users come later.
- **Yellow accent by default** (Plex-style `#E5A00D`); the other accent presets stay.
- **Icon:** the yellow accent with a bold, rounded chevron of our own. Never Plex's own logo shape, and the app never
  claims to be made or endorsed by Plex ("for Plex" wording only).
- **Never transcode** (a very important requirement): the owner's server is a low-powered machine, where transcoding
  never worked. Files are direct played; when the player can't read a file's container, it is direct streamed
  (repackaged, video and audio copied: `PlexClient.directStream`, which checks the server's decision says `copy`
  for every stream). Anything else is refused with a message, never sent for re-encoding. Live TV is direct
  streamed the same way. Don't use Plex's platform profiles (Android re-encodes past 1080p or stereo AAC).
- **Tuning a channel is slow** on the owner's server: Plex answers the tune before the tuner has locked (see
  docs/plex-api.md). Show "Tuning…" until the picture starts, let the player wait for the live stream to fill,
  and never retry a tune that timed out (a second tune can take a second tuner).
- **Live TV goes last:** on Home and in search results. In the menu, DVR is last, after Live TV (the owner, 2026-10-31).
- **DVR replaces Catch-up.** Record series defaults to new airings only (the owner, 2026-10-31). DVR comes before the
  series page redesign.
- **Home rows:** Continue watching, recently added series, recently added movies, favourite series, favourite movies,
  favourite Live TV channels. Empty rows are hidden.
- **Favourites** for channels, films and series stay as in MyTVPlayer.
- **Audio passthrough** (Dolby Atmos and other surround formats go to the soundbar or TV) is on by default, with a
  setting to turn it off.
- **Subtitles** default to forced subtitles only, in the system language. Settings choose the default language or
  turn subtitles off.
- **Movies and TV Shows grids** (0.10.0): Category, Filter and Sort pills above the grid (no category column). The
  remote's routes off the top row are the owner's rule, tested in `GridRoutesUiTest`: up goes to the pills only from a
  poster under them, otherwise to the menu; right off the row's end goes to the pills only from a short row.
- **Details pages** (0.10.0): picture size, HDR and sound show only for what has focus; More info holds the rest; the
  options menu (hold OK, or Menu) is shared by a title's page and the player (`titleActions`); the cast loads its
  photos only where shown.
- **Back stops playback.** There's no picture-in-picture.
- **Text fields are built for a TV remote:**
  - Server addresses get no autocorrect or capitals.
  - Passwords are shown, not hidden.
  - The keyboard's Next moves to the next field.
- **Options menu:** holding OK opens it, and so does the remote's Info/Menu button. Releasing OK must not also start
  playback.
- **Backup:**
  - Backup is one plain JSON file with a fixed name, overwritten each time, and no encryption or schedule.
  - Permissions are asked for only when backing up or restoring.
- **Licences** aren't a reason to avoid the best library.

## Open items (as of 0.3.0)
- **Release signing:** the owner added the `MYPLEX_*` keystore secrets on 2026-10-25, so releases from 0.3.0 on are
  signed with the release key (check the release run's "signed with" line). The first one won't install over the
  earlier development-key builds: the owner uninstalls once (backing up first; play state is on the Plex server).
  Releases stay pre-releases while the version is 0.x.
- **Google Play:** needs MyPlexPlayer's own Play listing and the `PLAY_SERVICE_ACCOUNT_JSON` secret; until then the
  release skips the upload.
- **Emulator smoke tests** on CI for pull requests (`.github/workflows/emulator.yml`: Android TV 9 and Google TV 16).
  Tests live in `app/src/androidTest`; the debug build adds an x86/x86_64 ABI only with `-PemulatorAbi=<arch>`.
  `.github/scripts/start-emulator.sh` boots the emulator itself: Google TV 16 drops off adb for a second or two after
  boot, which the usual runner action doesn't wait out.
