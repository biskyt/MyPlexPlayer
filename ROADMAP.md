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
   - Next: added guides and Catch-up, then Xtream; then the website and store listing.
4. **Home, menu and search:** Continue watching, recently added series and movies, favourite series and movies, then
   favourite Live TV channels. Live TV goes last in the menu, on Home and in search results. Empty rows are hidden.
5. **Playback from Plex:** direct play, with the server's transcoder as the fallback; resume points and watched state
   kept in Plex.
6. **Live TV and the TV guide from Plex.**
7. **DVR** in place of Catch-up: recordings, what's scheduled, and Record from the TV guide.

## Near future (owner's priorities, 2026-10-25)
- **Play state from the server:** what's been watched, and how far, comes from Plex and goes back to it, so it
  matches the owner's other Plex apps and devices. Today the app keeps its own resume points, and Plex's only start
  with step 5. With it, each series' poster gets a badge with its number of unwatched episodes (a show's `leafCount`
  less its `viewedLeafCount`).
- **Skip intro (and credits):** Plex marks a title's intro and credits (`includeMarkers=1` on a title's metadata
  gives its Marker list), as Plezy uses. As the owner set it out:
  - **Settings:** auto skip intro, and auto skip credits, each Off or a delay of 0 to 10 seconds (default 3).
  - **The delay counts down on a Skip pill:** it appears *delay* seconds before the skip, which happens 2 seconds
    after the marker's start (markers can be a little early), so with 3 the pill shows 1 second before the marker and
    skips 2 seconds after it, and with 5, 3 seconds before. Under 2 seconds the margin after the marker shrinks to the
    delay: with 0 the skip is exactly at the marker, with no pill before it.
  - **OK on the pill skips at once** (to the marker's end); **Back cancels** the automatic skip.
  - With auto skip off: a Skip pill while the intro or credits play, skipping only when pressed.
- **Bug: Dolby Vision files play in HDR10.** A file with Dolby Vision and HDR10 plays in Dolby Vision in Plex's own
  app and in Plezy (2.24.0) on the owner's TV, but in HDR10 here (and in MyTVPlayer: fix both). The app uses stock Media3,
  which hands a file to the TV's Dolby Vision decoder only for the cases it understands; others play their HDR10 base
  layer. Plezy (GPL-3.0, like this app) wraps the MKV and MP4 extractors, converts dual-layer Profile 7 (UHD Blu-ray)
  to single-layer Profile 8.1 with libdovi, and checks what the TV's decoders and display support. First: find the
  file's profile (Plex's media info says, as `DOVIProfile`) and what Media3 does with it on the TV; then port what's
  needed.

## Later
- Plex Home users (switching between the users of one account).
- Plex's newer JWT sign-in.
