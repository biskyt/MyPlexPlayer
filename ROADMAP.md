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
   - Next, once the owner's spike results are in: sign-in and servers, Live TV, the guide and DVR.
3. **Sign in to Plex and choose a server**, in place of Xtream, M3U, the free playlist and added guides. Several
   servers on one account, switched from the top bar. The website (`docs/index.html`, `docs/privacy.html`) and the
   store listing (`store/`) are rewritten for Plex in the same step: until then they still describe MyTVPlayer.
4. **Home, menu and search:** Continue watching, recently added series and movies, favourite series and movies, then
   favourite Live TV channels. Live TV goes last in the menu, on Home and in search results. Empty rows are hidden.
5. **Playback from Plex:** direct play, with the server's transcoder as the fallback; resume points and watched state
   kept in Plex.
6. **Live TV and the TV guide from Plex.**
7. **DVR** in place of Catch-up: recordings, what's scheduled, and Record from the TV guide.

## Later
- Plex Home users (switching between the users of one account).
- Plex's newer JWT sign-in.
