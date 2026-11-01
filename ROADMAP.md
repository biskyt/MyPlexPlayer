# MyPlexPlayer roadmap

MyPlexPlayer is a Plex client built from [MyTVPlayer](https://github.com/biskyt/MyTVPlayer)'s code. The two apps are
developed separately; fixes worth having in both are copied across by hand.

## 0.x: from MyTVPlayer to Plex
Each step is its own pull request.
0. **Plex API check and spikes:** every endpoint the app relies on is checked against
   [Plexopedia](https://www.plexopedia.com/plex-media-server/api/) and the official spec, then proven with a small
   spike. Results go in `docs/plex-api.md`.
1. **New app identity** (0.1.0): name, package, yellow accent, icon, docs and CI.
2. **Plex client** in `core`: plex.tv sign-in (PIN at plex.tv/link), servers and connections, libraries, hubs,
   playback, progress, Live TV, guide and DVR. Tested against fixtures captured from real servers.
   - Done: `PlexClient` for libraries, home rows, search, images, direct play, direct stream and progress, tested
     against recorded responses and, in the Plex spike workflow, against a real server (`PlexServerTest`).
   - Done: `PlexAccount` for sign-in (the code entered at plex.tv/link) and the account's servers, each at its
     best address (home network first, Plex's relay last), tested against plex.tv's replies on the owner's account.
   - Next: Live TV, the guide and DVR, as the steps below need them.
3. **Sign in to Plex and choose a server**, in place of Xtream, M3U, the free playlist and added guides. Several
   servers on one account, switched from the top bar. The website (`docs/index.html`, `docs/privacy.html`) and the
   store listing (`store/`) are rewritten for Plex in the same step: until then they still describe MyTVPlayer.
   - Done (0.2.0): Plex sign-in, each server a source, its films and series. Xtream, M3U and the free playlist are
     still there.
   - Done (0.2.2): M3U playlists and the free playlist removed; the emulator smoke test signs in to Plex.
   - Done (0.4.2): Catch-up removed.
   - Done (0.6.0): Xtream, added guides and guide matching removed; every test runs against a fake Plex server.
   - Next: the website and store listing.
4. **Home, menu and search:** Continue watching, recently added series and movies, favourite series and movies, then
   favourite Live TV channels. Live TV goes last in the menu, on Home and in search results. Empty rows are hidden.
   - Done (0.5.0): Home's rows in that order (recently added series by their newest episode, the server's
     `episode.addedAt` order per TV library), and Live TV last in the menu and search.
5. **Playback from Plex:** direct play, with the server repackaging a file the player can't open (direct stream,
   never converting); resume points and watched state kept in Plex.
   - Done (0.3.0): play state from and to the server (resume points, watched, Continue Watching as "Carry on
     watching", unwatched badges on series).
   - Done (0.3.4): direct stream for files the player can't open, and the token sent in headers rather than the
     address.
   - Done (0.3.5): Mark as watched / unwatched, from a poster's options and from Carry on watching.
   - Done (0.3.6): a playback info panel (More › Playback info while watching).
6. **Live TV and the TV guide from Plex.**
   - Done (0.4.0): the tuner's channels and the Plex guide (two days ahead, kept as the server's own guide source),
     tuning with "Tuning…" and the tuner let go on changing channel or leaving.
   - Done (0.6.0): the server's guide is the only one; why it last failed shows with the server.
7. **DVR** in place of Catch-up (next, before the series page redesign; the plan of 2026-10-31):
   - Spike S11 (series recordings: made, edited, one airing cancelled, removed; the mapping call).
   - Core and data: the DVR calls in `PlexClient`; the guide keeps each programme's `guid` and its show's.
   - Record from the TV guide and search: Record, Record series (new airings only by default), Cancel, the series
     recording's settings; marks on guide cells and results; one OK records with the defaults, More options shows
     the template's other settings.
   - A DVR tab, last in the menu: Recording now, Coming up, Series recordings, Problems. Finished recordings are
     library items (Recently added and the shared series and film pages play them), so the tab doesn't list them.
   - Done in 0.17.0: all of this step, except More options (the template's other settings): a series recording
     offers only new airings or repeats too.
   - Upcoming airings of a show, and the remote's Record key.
   - Scheduled recordings show a record icon on their TV guide cell (a series' airings too) and in search results.
   - While a channel plays, the player shows that its programme is recording (a red Recording mark with the clock),
     and the guide and the channel list mark what's recording now.
   - A toast whenever a scheduled recording starts or stops ("Recording started: {title}", "Recording finished" or
     "Recording stopped early"): on every screen, over playing video and over open menus and panels (panels are
     their own windows, so the toast needs its own window above them). The app learns of it from the scheduled
     list: timers at each recording's start and end (the list read again every 15 minutes and after any change),
     each confirmed with one call; Plex's notification socket may replace the timers later. Only while the app is
     open.
   - Done: watching recordings (S12, docs/plex-api.md 28). A channel that records now plays as usual. When its
     picture starts, the player offers "Watch live" and "Watch from start", only when the capture buffer holds more
     than a minute (also in More). There is no second tune. The tab also lists "Recently recorded": finished recordings still in a library, newest first.
     OK plays one. Holding OK opens its options: Play, and More info (its film's or series' page).

## Near future (owner's priorities, 2026-10-25)
- **Play state from the server:** done in 0.3.0 (see step 5).
- **Skip intro (and credits):** done in 0.8.0. Plex marks a title's intro and credits (`includeMarkers=1` on a title's metadata
  gives its Marker list), as Plezy uses. As the owner set it out:
  - **Settings:** auto skip intro, and auto skip credits, each Off or a delay of 0 to 10 seconds (default 3 for intros; credits Off, 0.8.1).
  - **The delay counts down on a Skip pill:** it appears *delay* seconds before the skip, which happens 2 seconds
    after the marker's start (markers can be a little early), so with 3 the pill shows 1 second before the marker and
    skips 2 seconds after it, and with 5, 3 seconds before. Under 2 seconds the margin after the marker shrinks to the
    delay: with 0 the skip is exactly at the marker, with no pill before it.
  - **OK on the pill skips at once** (to the marker's end); **Back cancels** the automatic skip.
  - With auto skip off: a Skip pill while the intro or credits play, skipping only when pressed.
- **Dolby Vision with HDR10+:** done in 0.3.2 (HDR10+ switched off for Dolby Vision decoders; port to MyTVPlayer).
  Profile 7 (dual layer) still plays its HDR10 base layer; converting it with libdovi isn't planned.

## Later
- Plex Home users (switching between the users of one account).
- Plex's newer JWT sign-in.
