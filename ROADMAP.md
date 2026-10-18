# MyTVPlayer roadmap

## Done
- **0.1–0.2:** Live TV (list and TV guide), player, guide matching, playlists (Xtream and M3U), free iptv-org channels, settings.

## Milestone 2: Movies, Series and search
- Movies and Series:
  - posters, plus optional TMDB artwork and details (can be turned off)
  - resume where you left off, watched state and "Carry on watching"
- Global search across channels, programmes, movies, series and episodes.
- Catch-up / timeshift and programme reminders.
- Backup and restore to a single JSON file.
- OpenSubtitles (off by default).

### "Find in Series / Movies" from the TV guide
In the TV guide, a programme's options menu (hold OK, or Info) offers a way to watch it on demand.

- **What the menu shows:**
  - **Series** (an episode number, or a series-type category): **Find series** opens the series, or the exact episode when it can be matched.
  - **Film:** **Find in Movies**.
  - **Clearly neither** (news, sport, live events): no option at all.
  - **Unknown:** **Search for "<title>"**, which opens search pre-filled with the title.
- **How it works:**
  - Take two things from the guide data the importer already reads: the XMLTV `<category>` and `<episode-num>` (`xmltv_ns` "S.E.P", or onscreen "S01E02").
  - Store them as a tiny `kind` (series / movie / other / unknown) plus season and episode numbers on each programme.
  - No extra downloads or lookups.
  - Search by normalised title (reusing the channel-name normaliser). It opens the series, then the episode if the season and episode numbers match.
  - If any of this turns out to be costly, fall back to the pre-filled search alone.

## Later
- Recording to USB storage (DVR).
- Touch support for phones, foldables and tablets: touch-friendly controls, phone layouts and player gestures.

## Not planned
PIN lock, multi-view, Stalker portals, Trakt, voice search, picture-in-picture.
