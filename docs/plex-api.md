# Plex client: API calls checked against Plexopedia and the official spec

This page records the Plex calls MyPlexPlayer relies on. First comes what the spikes proved
(`spikes/plex_spike.py`); then the desk check of each call against three sources, done on 2026-10-24:

- **Plexopedia** (the owner's preferred reference): https://www.plexopedia.com/plex-media-server/api/ and
  https://www.plexopedia.com/plex-media-server/api-plextv/. Every page in both indexes and the site sitemap that
  relates to the app was read. Plexopedia documents **XML responses** and passes the token as the query
  parameter `X-Plex-Token`. Its API home page says JSON is returned with the header `Accept: application/json`.
  **Plexopedia has no pages for:** the plex.tv sign-in PIN flow, `/api/v2/resources`, `/:/timeline`, the universal
  transcoder, hubs (other than `/hubs/search`), Live TV, the EPG or DVR. Its "PIN" pages (`pin-admin-*`,
  `pin-managed-user-*`) cover Plex Home user PINs (`PUT https://plex.tv/api/home/users/{user_id}?pin=`), which is a
  different feature.
- **Official OpenAPI spec**: "Plex Media Server", OpenAPI 3.1.0, `info.version` "1.2.3" (from https://developer.plex.tv/pms/). Its responses are JSON (`application/json`). Its intro says XML is
  the default and JSON needs `Accept: application/json`. The spec covers **only the server (PMS)**. plex.tv appears
  only in the prose section "Authenticating with Plex" in `info.description`.
- **Plex forum guide** "Authenticating with Plex" (https://forums.plex.tv/t/authenticating-with-plex/609370, 2020).
  The spec's section "Traditional Token Authentication (Legacy)" is the same text, plus a "Talking to PMS" paragraph on
  `/api/v2/resources` and a newer section "JWT Authentication (Recommended)".

"Agrees?" means: **Yes** when both sources document the call the same way; **Partly** when both cover it but differ;
**Spec only** or **Plexopedia only** when one source covers it; **Neither** when no source documents it as a
path. The spec's JSON uses `MediaContainer.Metadata[]` for items, while Plexopedia's XML uses `<Video>` (movies,
episodes) and `<Directory>` (shows, seasons, libraries). The attribute names are the same in both.

## What the spikes proved

Run against a real Plex Media Server 1.43.5 (unclaimed, with a generated film and series; `.github/scripts/start-plex.sh`
and the **Plex spike** workflow): `library` 36 checks and `playback` 6 checks, all passing. Sign-in, servers, Live TV
and DVR need a real account and tuner: the owner runs those (see `spikes/README.md`), and their results go below.

| # | Question | Answer | How |
|---|---|---|---|
| 4 | Library list path | Both `GET /library/sections` and the spec's `/library/sections/all` work, with the same result. Use `/library/sections`. | S3 |
| 5 | Paging `/library/sections/{id}/all` | `type=1` (movies) / `type=2` (shows) work, and paging works both as `X-Plex-Container-Start`/`-Size` headers and as query parameters. `MediaContainer.totalSize` and `offset` come back. | S3 |
| 6 | Seasons and episodes | `/library/metadata/{show}/children` gives seasons (`type` "season"), `/{season}/children` gives episodes with `index`; `/{show}/allLeaves` gives every episode. | S3 |
| 7 | Continue watching | `/hubs/continueWatching` (one `Hub`, `hubIdentifier` "continueWatching", items with `viewOffset`) works, and so do `/hubs/home/continueWatching`, `/hubs/home/onDeck` and `/library/onDeck`. It holds **one item per show** (the latest episode in progress). Use `/hubs/continueWatching`. | S3 |
| 7 | Recently added movies | `/hubs/home/recentlyAdded?type=1` gives movies. | S3 |
| 7 | Recently added series | `/hubs/home/recentlyAdded?type=2` gives **seasons**, not shows, and `/library/sections/{id}/recentlyAdded` gives **episodes**. For a row of shows use `/library/sections/{id}/all?type=2&sort=episode.addedAt:desc` with a container size (`episode.addedAt` is one of the section's own sorts, from `/library/sections/{id}?includeDetails=1`), per TV library. | S3 |
| 8 | Resume points | `POST /:/timeline` (with `X-Plex-Session-Identifier`) stores `viewOffset`, and the item then shows in Continue watching; Plexopedia's `PUT /:/progress?key=&identifier=com.plexapp.plugins.library&time=&state=stopped` stores it too. **PMS ignores progress in a title's first 60 s** (its log: "Ignoring premature play progress"; Plexopedia's `minimumProgressTime`). | S3 |
| 8 | Watched | **The server marks a title watched itself** when one timeline session plays on past its threshold (90% by default, `LibraryVideoPlayedThreshold` in `/:/prefs`), and clears the resume point. Reports must come from one session that moves forward: a single report near the end, with no playing before it, isn't counted. So the app does **not** call `/:/scrobble` after playback (it would count twice, as Plezy notes); `PUT /:/scrobble` and `PUT /:/unscrobble` are for "Mark as watched / unwatched". `unscrobble` also clears the resume point. | S3 |
| 9 | Transcoder | `GET /video/:/transcode/universal/decision` and `start.m3u8` need a client profile, or PMS answers "No conversion profile found for protocol hls" (400 for start.m3u8). Send `X-Plex-Client-Profile-Extra: add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts&videoCodec=h264&audioCodec=aac)` (or `X-Plex-Client-Profile-Name: Android`). `path=/library/metadata/{ratingKey}`, `offset` in seconds, and `X-Plex-Session-Identifier` / `session` work; the playlist's variant path is relative to `/video/:/transcode/universal/`. `GET .../universal/stop?session=` ends it. | S4 |
| 10 | Direct play | `Part.key` (`/library/parts/{id}/{changestamp}/file.mp4`), fetched as the player will (the token in the URL, no X-Plex headers), answers HTTP `Range` requests with 206 and `Accept-Ranges: bytes`, without a decision call first. | S4 |
| 11 | Images | `/photo/:/transcode?url={thumb}&width=&height=&minSize=1&upscale=1` returns a JPEG. Plezy puts the token both in the encoded inner `url` and on the outer query; to check on a signed-in server. | S3 |
| 16 | Transient token | `/security/token` answers 400 on an unclaimed server; to check on the owner's server. It isn't needed for now. | S3 |
| - | Search | `/hubs/search?query=&limit=` finds films and shows, in hubs by type. | S3 |

Still open (owner's server): 1-3 (sign-in and servers), 11 (the image token), 12-15 (Live TV, guide and DVR).

### What Plezy does (Live TV, DVR and sign-in)
[Plezy](https://github.com/edde746/plezy) (GPL-3.0, a Plex and Jellyfin client) answers most of the Live TV questions;
the `livetv` and `dvr` spikes follow it, to be confirmed on the owner's server. Read at commit `d1c957ba`; only how it
uses the API was taken, no code.
- **Guide:** `/media/providers` gives the guide provider (`protocols` contains `livetv`) and its `grid` feature key.
  One call covers every channel: `GET {grid}?endsAt>={from}&beginsAt<={to}` (epoch seconds; the parameter names are
  `endsAt>` and `beginsAt<`). Programmes are `Metadata[]`, with times in `Media[].beginsAt/endsAt` and the channel in
  `Channel[0].id` or `Media.channelIdentifier`. So the per-channel, per-day `channelGridKey`/`date` form isn't needed.
- **Channels:** `/lineups/plex/channels`, falling back to `/{provider}/lineups/dvr/channels`. A channel's key is
  `key`, else `ratingKey`, `identifier` or `id`; only channels in the DVR's enabled `ChannelMapping.channelKey` are
  shown. `/livetv/dvrs` returns `Dvr[]`.
- **Playing a channel:** `POST /livetv/dvrs/{dvr.key}/channels/{channel key}/tune?X-Plex-Session-Identifier={id}`
  (never retried on a timeout: a second tune can take a second tuner). The result is in
  `MediaContainer.MediaSubscription[0].MediaGrabOperation[0].Metadata` (else `Metadata[0]`): its `key` is the
  session's path (`/livetv/sessions/…`) and `ratingKey` the programme. That path is played through the universal
  transcoder (`decision`, then `start.m3u8` with `path={session path}`, `directPlay=0`, `directStream=1`). Live never
  direct plays. A `/:/timeline` heartbeat every 10 s keeps it going (`key={session path}`, `duration` at least
  `time`, or 400), and `state=stopped` frees the tuner. `TranscodeSession` gives the time-shift window.
- **DVR:** template, `POST /media/subscriptions?{template parameters}&targetLibrarySectionID&type`,
  `GET /media/subscriptions/scheduled` (`MediaGrabOperation[]`), `DELETE /media/subscriptions/{id}` for a rule and
  `DELETE /media/grabbers/operations/{id}` for one airing.
- **Sign-in:** Plezy uses the long PIN (`strong=true`) and app.plex.tv/auth in a web view or QR code, with a legacy
  token. MyPlexPlayer's plan is the short code at plex.tv/link (the `signin` spike checks it).
- **Connections:** it races `GET /` with the token on every connection (direct before relay; local, then remote,
  then relay; HTTPS and plex.direct preferred).

## Common: headers, format, paging

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `X-Plex-*` headers | https://www.plexopedia.com/plex-media-server/api/ (token only) | `info.description` › Headers; header params `X-Plex-Client-Identifier` (required on transcode and timeline), `X-Plex-Session-Identifier`, `X-Plex-Client-Profile-Name`, `X-Plex-Client-Profile-Extra`; security scheme `user_token` = header `X-Plex-Token` | Partly | Spec list: `X-Plex-Client-Identifier`, `X-Plex-Token`, `X-Plex-Product`, `X-Plex-Version`, `X-Plex-Platform`, `X-Plex-Platform-Version`, `X-Plex-Device`, `X-Plex-Model`, `X-Plex-Device-Vendor`, `X-Plex-Device-Name`, `X-Plex-Marketplace`. "`X-Plex-Client-Identifier` is typically required, as is `X-Plex-Token`". All `X-Plex-` headers may also be sent as query parameters. Plexopedia only ever sends `X-Plex-Token` as a query parameter. The spec also defines `X-Plex-Pms-Api-Version` (none sent = API `0.0`). |
| JSON vs XML | API home (JSON via `Accept: application/json`) | `info.description` › Content Types | Yes | XML is the default in both. The spec says new apps should use JSON. All of Plexopedia's examples and field tables are XML. |
| Paging | not covered (pages offer `limit` only) | `info.description` › Pagination | Spec only | Request headers `X-Plex-Container-Start` and `X-Plex-Container-Size`. Response headers `X-Plex-Container-Start` and `X-Plex-Container-Total-Size`. Body `MediaContainer.offset`, `.size`, `.totalSize` ("optional but typically present"). `limit` (a media query parameter) caps the total and can be combined with paging. The server may force paging when a client asks for everything. |

## plex.tv: sign-in and servers

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `POST /api/v2/pins` (short code for plex.tv/link) | not covered | not in spec paths (prose only: "Authenticating with Plex") | Spec prose and forum agree | Forum: `POST https://plex.tv/api/v2/pins` with `strong=true` sent as form data (`-d`). Spec legacy section: `POST https://plex.tv/api/v2/pins?strong=true`, header `X-Plex-Product` and `X-Plex-Client-Identifier`. Spec JWT section: `POST https://clients.plex.tv/api/v2/pins` with a JSON body `{"jwk": {...}, "strong": true}`. **For the short code, omit `strong`:** "If not specified, a shorter pin is created but will have a much shorter lifetime." The spec's JWT section says "For 4-digit pins, you need to use the link page: `https://plex.tv/link/?pin=<code>`". Documented response fields are only `id` and `code`. Neither source documents `expiresIn`, `expiresAt` or the code's length and alphabet. |
| `GET /api/v2/pins/{id}` (`authToken`) | not covered | not in spec paths (prose only) | Spec prose and forum agree | `GET https://plex.tv/api/v2/pins/<pinID>` with `X-Plex-Client-Identifier`, `Accept: application/json`. The forum also sends `code=<pinCode>`; the spec's version doesn't. `authToken` stays `null` until the PIN is claimed. Poll "once per second". JWT variant: `GET https://clients.plex.tv/api/v2/pins/<pinID>?deviceJWT=<signedJWT>` returns a 7-day JWT in `authToken`. |
| `GET /api/v2/user` | https://www.plexopedia.com/plex-media-server/api-plextv/user/ documents **`GET https://plex.tv/api/v2/home/user`** | not in spec paths (prose only) | Partly: the path differs | Forum and spec: `GET https://plex.tv/api/v2/user` with `X-Plex-Product`, `X-Plex-Client-Identifier`, `X-Plex-Token`. `200` means the token is valid, `401` means it's invalid, anything else is an error state and doesn't prove the token is invalid. Plexopedia's `/api/v2/home/user` (XML `<user>`) lists `id`, `uuid`, `username`, `title`, `email`, `friendlyName`, `thumb`, `authToken`, `locale`, `restricted`, `home`, `homeAdmin`, `subscription` and more. Neither source documents the JSON field names of `/api/v2/user`. |
| `GET /api/v2/resources?includeHttps=1&includeRelay=1` | https://www.plexopedia.com/plex-media-server/api-plextv/resources/ documents the **legacy `GET https://plex.tv/resources`** (XML, no include flags) | not in spec paths (prose only: "Talking to PMS") | Partly | Spec prose: `GET https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=1` with `Accept: application/json`, `X-Plex-Product`, `X-Plex-Client-Identifier`, `X-Plex-Token`. It returns servers, "the `accessToken` used in communication with this PMS", and connection URLs. "Connections labeled as `local` should be preferred … `relay` should only be used as a last resort". Plexopedia (legacy XML): `<Device>` attributes include `name`, `product`, `clientIdentifier`, `provides` (e.g. "server", "client,player,pubsub-player"), `owned`, `accessToken`, `publicAddress`, `httpsRequired`, `relay` (a **device-level** "relay is enabled" flag), `presence`. `<Connection>` attributes: `protocol`, `address`, `port`, `uri`, `local`. The v2 JSON names (for example a `connections` array, or a per-connection `relay`) are **not confirmed** by either source. |
| Older server list | https://www.plexopedia.com/plex-media-server/api-plextv/servers/ (`GET https://plex.tv/servers.xml?includeLite=1`) | not in spec | Plexopedia only | It isn't in our plan. Listed only because Plexopedia offers it as an alternative (`<Server accessToken= machineIdentifier= localAddresses= …>`). |

## Server: identity and libraries

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `GET /identity` | https://www.plexopedia.com/plex-media-server/api/server/identity/ | `GET /identity` (`getIdentity`) | Yes | Fields: `MediaContainer.claimed`, `.machineIdentifier`, `.version`, `.size`. Plexopedia: "does not require a Plex token". XML `claimed="1"` versus spec boolean `claimed`. |
| `GET /library/sections` | https://www.plexopedia.com/plex-media-server/api/server/libraries/ (`GET /library/sections/`) | **`GET /library/sections/all`** (`libraryGetSections`); the `/media/providers` example's `content` feature key is `/library/sections` | Partly: the path differs | Spec intro examples and the JWT example use `/library/sections`, but the documented operation path is `/library/sections/all`. The items are `MediaContainer.Directory[]` with `key` (the section id, e.g. "2"), `type` ("movie" / "show"), `title`, `uuid` (Plexopedia; missing from the spec's `librarySection` schema), `agent`, `language`, `hidden`, `art`, `thumb`. Non-admin tokens see only the sections they can access (Plexopedia). |
| `GET /library/sections/{id}` (section details) | https://www.plexopedia.com/plex-media-server/api/library/details/ | `GET /library/sections/{sectionId}` | Yes | It's optional for us. Its `Directory` keys include `all`, `recentlyAdded`, `onDeck` ("Continue Watching"), `search?type=1`. The spec adds `includeDetails=1` (types, filters, sorts in one call). |
| `GET /library/sections/{id}/all?type=1\|2` + paging | https://www.plexopedia.com/plex-media-server/api/library/movies/ and /tvshows/ | `GET /library/sections/{sectionId}/all` (`librarySectionGetAll`, param `mediaQuery`) | Partly | Plexopedia: `includeGuids`, `limit` and field filters (`addedAt>=`, `year=`, …; see /filter/). It doesn't mention `type` or paging. Spec: `type` comes from media queries ("A `type` parameter must be included to specify the result type"), plus `sort`, `limit`, and paging through `X-Plex-Container-Start` / `-Size`. Response: `MediaContainer.size`, `totalSize`, `offset`, `Metadata[]`. Item fields (both): `ratingKey`, `key` (`/library/metadata/{ratingKey}`), `guid`, `type`, `title`, `year`, `summary`, `thumb`, `art`, `duration` (ms), `addedAt`, `viewCount`, `viewOffset`, `lastViewedAt`; shows also have `leafCount`, `viewedLeafCount`, `childCount`. `Media[].Part[].key` is the playable path. |
| `GET /library/metadata/{id}` | https://www.plexopedia.com/plex-media-server/api/library/movie/ | `GET /library/metadata/{ids}` (array: comma-separated ids allowed) | Yes | Both accept `includeGuids` (Plexopedia) / various `async*`/`checkFiles` flags (spec). Fields as above plus `Media[]` (`id`, `container`, `videoCodec`, `audioCodec`, `videoResolution`, `bitrate`) and `Part[]` (`id`, `key`, `file`, `size`, `container`, `duration`). |
| `GET /library/metadata/{id}/children` | https://www.plexopedia.com/plex-media-server/api/library/tvshows-seasons/ (show to seasons) and /tvshows-episodes/ (season to episodes) | **not in spec paths.** The spec prose only says `/children` is "One exception … for parents like shows and seasons" | Plexopedia only | Seasons: `<Directory>` with `ratingKey`, `key`, `index`, `title`, `leafCount`, `viewedLeafCount`, `parentRatingKey`. Episodes: `<Video>` with `index`, `parentIndex`, `grandparentTitle`, `viewOffset`/`viewCount` (when set), `Media`/`Part`. |
| `GET /library/metadata/{id}/allLeaves` | not covered | `GET /library/metadata/{ids}/allLeaves` ("the episodes in a show") | Spec only | `MediaContainer.Metadata[]` with paging fields. |

## Server: home rows and search

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `GET /hubs/continueWatching` | not covered | `GET /hubs/continueWatching` (param `count`) | Spec only | "Get the global continue watching hub". The response is `MediaContainer.Hub[]` (`hubIdentifier`, `key`, `title`, `type`, `size`, `more`, `Metadata[]`). The example's hub: `key` "/hubs/home/continueWatching", `hubIdentifier` "home.continue". `/media/providers` lists it as the `continuewatching` feature. |
| `GET /hubs/home/continueWatching` | not covered | not a path. It appears only as a Hub `key` value in examples | Neither | It is probably what the Hub's `key` points to (the "more" link). Unverified. |
| `GET /library/onDeck` | not covered (Plexopedia has only the per-section **`GET /library/sections/{id}/onDeck`**: https://www.plexopedia.com/plex-media-server/api/library/movies-on-deck/) | not in spec (the example Hub `key` is "/hubs/home/onDeck") | Neither (global); Plexopedia only (per section) | Per section: `<Video>` items with `viewOffset`, `viewCount`, `lastViewedAt`. Plexopedia: the capital `D` matters; `ondeck` returns 404. |
| `GET /hubs/home/recentlyAdded?type=1\|2` | not covered | not a path. Example Hub `key`s are "/hubs/home/recentlyAdded?type=1", "?type=2", "?type=1&personal=1" | Neither | Reachable only by following a Hub `key` from `GET /hubs`, `/hubs/promoted` or `/hubs/sections/{sectionId}`. Those are spec paths with `count` and `onlyTransient=1`. |
| `GET /library/recentlyAdded` | https://www.plexopedia.com/plex-media-server/api/library/recently-added/ | not in spec | Plexopedia only | Movies, TV, music and other videos, but no photos. The capital `A` matters, or it's a 404. No parameters are documented. |
| `GET /library/sections/{id}/recentlyAdded` | https://www.plexopedia.com/plex-media-server/api/library/movies-recently-added/ and /tvshows-recently-added/ | not in spec (it appears only as a `Directory` `key` "recentlyAdded" in a section example) | Plexopedia only | For a TV section the items are **episodes** (`<Video>` with `grandparentRatingKey`, `parentRatingKey`), not shows. |
| `GET /hubs/search?query=` | https://www.plexopedia.com/plex-media-server/api/server/search/ | `GET /hubs/search` | Yes | Params: `query` (required), `limit` (per hub, default 3), `sectionId`. Response: `Hub[]` (`type`, `hubIdentifier`, `size`, `more`), with `Metadata[]` / `<Video>` (has `score`), and `Directory` for actors and genres. Spec: 400 when a parameter is missing, 404 when no sections are searchable. |
| `GET /search?query=` | not covered (Plexopedia's "Search for Match" is metadata matching) | not in spec | Neither | Don't use it. Use `/hubs/search`. |

## Server: images and playback

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| Image `GET /photo/:/transcode` | not covered (Plexopedia fetches `GET {thumb}` / `GET {art}` directly: https://www.plexopedia.com/plex-media-server/api/library/movie-poster/, /movie-background/) | `GET /photo/:/transcode` | Spec only (direct path: Plexopedia; it matches the spec's `GET /library/metadata/{ids}/{element}/{timestamp}`) | Params: `url`, `width`, `height`, `minSize`, `upscale`, `format` (`jpg`/`jpeg`/`png`/`ppm`), `quality` (-1 = best), `blur`, `opacity`, `saturation`, `background`, `blendColor`, `chromaSubsampling`, `rotate`. "If this URL requires a token such as `X-Plex-Token`, it should be given as a query parameter to this url" (the inner `url`). |
| Direct play `GET /library/parts/{id}/{ts}/file.ext` | https://www.plexopedia.com/plex-media-server/api/library/download-media-file/ (as a download: `GET {Part.key}?download=0\|1`) | `GET /library/parts/{partId}/{changestamp}/{filename}` | Yes, on the path | Use `Part.key` as given (e.g. `/library/parts/73812/1450141780/file.mkv`). Spec: streaming is the default; `download=1` lowers its bandwidth priority; 503/509 can come back "without a decision" ("Clients should not rely on ad-hoc decisions"). Plexopedia: 403 if the user may not download. Seeking with HTTP `Range` isn't documented by either source. |
| `GET /video/:/transcode/universal/decision` | not covered | `GET /{transcodeType}/:/transcode/universal/decision` (`transcodeType` = `video`/`music`/`audio`/`subtitles`) | Spec only | Query: `path` ("Internal PMS path of the media", i.e. `/library/metadata/{ratingKey}`), `mediaIndex`, `partIndex`, `offset` (**seconds**), `protocol` (`http`/`hls`/`dash`), `directPlay`, `directStream`, `directStreamAudio`, `videoResolution`, `videoBitrate`, `peakBitrate` (no `maxVideoBitrate` is listed), `audioChannelCount`, `subtitles` (`auto`/`burn`/`none`/`sidecar`/`embedded`/`segmented`), `subtitleSize`, `location` (`lan`/`wan`/`cellular`), `mediaBufferSize`, `autoAdjustQuality`, `transcodeSessionId`. Headers: `X-Plex-Client-Identifier` (required), `X-Plex-Session-Identifier`, `X-Plex-Client-Profile-Name`, `X-Plex-Client-Profile-Extra`, `X-Plex-Platform`, `X-Plex-Device`. Response: `generalDecisionCode`/`Text`, `directPlayDecisionCode`/`Text`, `transcodeDecisionCode`/`Text`, `mdeDecisionCode`, `availableBandwidth`, and `Metadata[].Media[].Part[].decision` (example: "transcode"). **No `session` query parameter is listed.** |
| `GET /video/:/transcode/universal/start.m3u8` | not covered (Plexopedia has only the list `GET /transcode/sessions/` and `DELETE {TranscodeSession.key}`: https://www.plexopedia.com/plex-media-server/api/server/transcode-sessions/, /session-transcode-terminate/) | `GET /{transcodeType}/:/transcode/universal/start.*` | Spec only | Same parameters and headers as `decision`. Returns m3u8 (`application/vnd.apple.mpegurl`), MPD or an MKV stream. The spec documents no stop or ping call for the universal session apart from `/:/timeline` `state=stopped`. |
| `POST /:/timeline` | not covered | `POST /:/timeline` | Spec only | Query: `ratingKey`, `key`, `state` (`stopped`/`buffering`/`playing`/`paused`), `time` (ms), `duration` (ms), `playQueueItemID`, `continuing`, `timeToFirstFrame`, `timeStalled`, `bandwidth`, `bufferedTime`. Headers: `X-Plex-Client-Identifier` (required), `X-Plex-Session-Identifier`. Send it "whenever the play state changes", otherwise "every 10 seconds on a LAN/WAN, and every 20 seconds over cellular". **The spec documents only POST**, not GET. The response is a server-capabilities `MediaContainer` plus `terminationCode`/`terminationText`. |
| `/:/scrobble` (mark watched) | https://www.plexopedia.com/plex-media-server/api/library/media-mark-watched/ (**GET**) | `PUT /:/scrobble` | Partly: the method differs | Both: `identifier=com.plexapp.plugins.library` (required in the spec), `key=<ratingKey>`. The spec also allows `uri`. Spec: "This API does respond to the GET verb but applications should use PUT". Returns 200 with no body. Spec: "does not create any view history". |
| `/:/unscrobble` (mark unwatched) | https://www.plexopedia.com/plex-media-server/api/library/media-mark-unwatched/ (**GET**) | `PUT /:/unscrobble` | Partly: the method differs | Same as scrobble. |
| `/:/progress` ("Update Play Progress") | https://www.plexopedia.com/plex-media-server/api/server/update-media-progress/ documents **`PUT /:/progress?key={id}&identifier=com.plexapp.plugins.library&time={ms}&state={state}`** | not in spec | Plexopedia only | Plexopedia: `time` must exceed the server's `minimumProgressTime` (default 60000 ms, so at least 60001). To store progress, set `state=stopped`. Returns 200 with no body. The spec's equivalent mechanism is `/:/timeline`. |
| `GET /status/sessions` | https://www.plexopedia.com/plex-media-server/api/server/sessions/ | `GET /status/sessions` | Yes | Not needed for playback; listed for completeness. |
| Transient token (for URLs handed to the player) | https://www.plexopedia.com/plex-media-server/api/server/transient-token/ (**`GET /security/token`**, no params) | **`POST /security/token?type=delegation&scope=all`** | Partly | Valid up to 48 h, cleared on server restart; the response field is `MediaContainer.token`. The spec says it "responds to all HTTP verbs but POST in preferred" and that `type` and `scope` are required. It isn't in our plan, but it's useful if tokens shouldn't appear in player logs. |

## Server: Live TV

Plexopedia covers none of this section.

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `GET /media/providers` | not covered | `GET /media/providers` | Spec only | `MediaProvider[]` with `identifier`, `title`, `types`, `protocols`, and `Feature[]` (`type`, `key`). Example EPG provider features: `content` "/tv.plex.providers.epg.cloud:12/sections", `grid` "/tv.plex.providers.epg.cloud:12/grid", `search`, `metadata`, `subscribe`, `timeline`. **Take the EPG base path from here** rather than building it. |
| `GET /livetv/dvrs` | not covered | `GET /livetv/dvrs` | Spec only | Schema array name `DVR`; the **example uses `Dvr`**. Fields: `key` (DVR id), `uuid`, `lineup` (e.g. "lineup://tv.plex.providers.epg.onconnect/…"), `language`, `Device[]` (`key`, `uuid`, `make`, `model`, `uri`, `tuners`, `status`), `ChannelMapping[]` (`channelKey`, `deviceIdentifier`, `enabled`, `lineupIdentifier`). |
| Channels: `GET /tv.plex.providers.epg.{identifier}:{deviceId}/lineups/dvr/channels` | not covered | same path; `identifier` enum `xmltv`/`cloud`/`custom`, `deviceId` integer, optional `genre` | Spec only | **The schema and example disagree.** The schema has `identifier`, `key`, `channelVcn`, `hd`, `thumb`, `title`, `callSign`, `language`; the example has `id`, `gridKey`, `vcn`, `isHd`, `thumb`, `title`, `callSign`, `language`. In the example, `gridKey` = "5fc76bd6…-5fc705db…" (two ids joined by `-`). `deviceId` is described as "The device's Identifier", but the examples use the provider number (e.g. `cloud:11`). |
| Channels: `GET /livetv/epg/channels?lineup=` | not covered | `GET /livetv/epg/channels` (`lineup` required: "The URI describing the lineup") | Spec only | It's meant for DVR setup (channels of a lineup). Probably use `/lineups/dvr/channels` for the guide. |
| Guide grid | not covered | `GET /tv.plex.providers.epg.{identifier}:{deviceId}/grid?channelGridKey=&date=` (both required; `date` format `date`, example "2023-01-15") | Spec only | **One channel, one day per call.** Items: `Metadata[]` (`title`, `grandparentTitle`, `type`, `ratingKey`, `key`, `guid`, `thumb`) with `Media[]`: `beginsAt`, `endsAt` (epoch s), `channelIdentifier`, `channelID`, `channelVcn`, `channelTitle`, `channelCallSign`, `channelThumb`, `gridKey`, `protocol` "livetv". Note that `Media.gridKey` in the example is only the second half of the channel's `gridKey`. There are also `/watchnow` and `/watchnow/all` (what's on now), not in our plan. |
| Tune: `POST /livetv/dvrs/{dvrId}/channels/{channel}/tune` | not covered | same path; `dvrId` integer, `channel` string (example "2.1") | Spec only | Returns `Metadata[]` with `live: true` and `Media[].uuid`, `Part[].Stream[]` (codecs). **No playable URL or session id is returned in the example.** 500 = "Tuning failed". Which value goes in `{channel}` (the VCN, `ChannelMapping.channelKey` or `deviceIdentifier`) isn't stated. |
| Live playback URL after tuning | not covered | `GET /livetv/sessions/{sessionId}/{consumerId}/index.m3u8` (and `/{segmentId}`) | Spec only, incomplete | The spec doesn't say where `sessionId` and `consumerId` come from (not from the tune response). It's unknown whether tune's `Media.uuid` is the session id, or whether the universal transcoder should be used instead. |
| `GET /livetv/sessions` | not covered | `GET /livetv/sessions`, `GET /livetv/sessions/{sessionId}` | Spec only | `MediaContainer.Metadata[]`. The spec gives no example, so the fields aren't documented. |

## Server: DVR recordings

Plexopedia covers none of this section.

| Call | Plexopedia page | Official spec path | Agrees? | Notes |
|---|---|---|---|---|
| `GET /media/subscriptions/template?guid=` | not covered | `GET /media/subscriptions/template` (`guid`) | Spec only | `SubscriptionTemplate[].MediaSubscription[]` with `title` (e.g. "This Episode"), `type`, `targetLibrarySectionID`, `selected`, `airingsType` (e.g. "New Airings Only"), `Setting[]`, `Video`, and **`parameters`**: a ready URL-encoded string of `hints[...]` and `params[...]` (e.g. `params[airingChannels]`, `params[airingTimes]`, `params[mediaProviderID]`). 403 = "User cannot access DVR on this server". |
| `POST /media/subscriptions` | not covered | `POST /media/subscriptions` | Spec only | Query (deepObject): `targetLibrarySectionID`, `targetSectionLocationID`, `type`, `hints[...]`, `prefs[...]`, `params[...]`. "Should be mostly derived from the template". Returns `MediaSubscription[]`. 409 = a subscription with the same parameters already exists. |
| `GET /media/subscriptions` | not covered | `GET /media/subscriptions` (`includeGrabs`, `includeStorage`) | Spec only | `MediaSubscription[]`: `key` (example "1", the id used for DELETE), `title`, `type`, `targetLibrarySectionID`, `Setting[]`, `MediaGrabOperation[]`. |
| `GET /media/subscriptions/scheduled` | not covered | `GET /media/subscriptions/scheduled` | Spec only | `MediaGrabOperation[]`: `mediaSubscriptionID`, `status` (e.g. "scheduled"), `grabberIdentifier`, `percent`, `Metadata` (with `Media[].beginsAt`, `channelIdentifier`). In the example, `id` and `key` are `null`. |
| `DELETE /media/subscriptions/{id}` | not covered | `DELETE /media/subscriptions/{subscriptionId}` (integer) | Spec only | "Delete a subscription, cancelling all of its grabs as well". 403 and 404 are possible. The spec has no call that cancels **one** scheduled airing; the spec's path list shows none. |

## Open questions (as first listed)

Each item below was confirmed by at most one source, or the two sources disagreed. The table above says which
are now answered.

Each item below is confirmed by at most one source, or the two sources disagree. Try each against a real server.

1. **Short PIN for plex.tv/link.** Confirm that `POST https://plex.tv/api/v2/pins` without `strong` returns a short
   `code` that https://plex.tv/link accepts. Check the JSON fields `expiresIn` / `expiresAt` (they aren't documented)
   and the code's length and lifetime. Also check whether `X-Plex-Product` is required. Decide whether to use the
   legacy token or the JWT flow (`clients.plex.tv` + `jwk` + `deviceJWT`). A forum report says the 4-character code
   path returns a legacy token, not a JWT.
2. **`/api/v2/user` vs `/api/v2/home/user`.** Plexopedia documents only `/api/v2/home/user`; the forum and spec use
   `/api/v2/user`. Neither gives `/api/v2/user`'s JSON field names.
3. **`/api/v2/resources` JSON shape.** Only legacy XML `/resources` is field-documented (Plexopedia). Confirm the JSON
   names `provides`, `accessToken`, `clientIdentifier`, the `connections` array and its `uri` / `local` / `relay` /
   `protocol` fields, and which host to use (`plex.tv` or `clients.plex.tv`).
4. **Library list path.** The spec's operation is `GET /library/sections/all`; Plexopedia, the spec's intro and the
   media-provider `content` key all use `GET /library/sections`. Check that both work.
5. **Paging on `/library/sections/{id}/all`.** `X-Plex-Container-Start` / `-Size` and `totalSize` come from the spec
   only, and so does `type=1|2`. Check that both work as headers and as query parameters.
6. **`/library/metadata/{id}/children`.** Plexopedia only. It's not a spec path. `allLeaves` is in the spec only.
7. **Continue Watching and Recently Added.** `/hubs/continueWatching` is spec-only. `/hubs/home/continueWatching`,
   `/hubs/home/onDeck` and `/hubs/home/recentlyAdded?type=` appear only as Hub `key` values in spec examples.
   `/library/recentlyAdded` and `/library/sections/{id}/recentlyAdded|onDeck` are Plexopedia-only. A global
   `/library/onDeck` is in **neither** source. Pick one route per row and confirm it returns `viewOffset` and
   `type=1|2` filtering.
8. **Watched state and progress methods.** For scrobble and unscrobble, Plexopedia uses GET and the spec says PUT. For
   progress, Plexopedia's `PUT /:/progress` (with `time` above 60000 ms) isn't in the spec. The spec's
   `POST /:/timeline` isn't in Plexopedia, and GET `/:/timeline` is in neither. Decide whether timeline alone
   stores the resume point, or whether `/:/progress` is also needed on stop.
9. **Universal transcoder.** Spec only. Check the exact `path` value, that `offset` is in seconds, whether a `session`
   parameter is still needed alongside `transcodeSessionId` / `X-Plex-Session-Identifier`, which profile name to send
   for ExoPlayer, and how to stop a session (Plexopedia's `DELETE {TranscodeSession.key}` or timeline
   `state=stopped`).
10. **Direct play.** Check that `Part.key` with `X-Plex-Token` streams with HTTP `Range` (seek) and doesn't hit the
    spec's 503/509 "no decision" case without calling `decision` first.
11. **Images.** For `/photo/:/transcode`, confirm whether the token goes inside `url`, outside it, or both.
    Plexopedia uses the raw `thumb` path plus the token.
12. **Live TV channel and grid.** The channel field names differ between the spec's schema (`identifier`, `key`,
    `channelVcn`, `hd`) and its example (`id`, `gridKey`, `vcn`, `isHd`). Find out what `{deviceId}` in
    `tv.plex.providers.epg.{identifier}:{deviceId}` is (use the `/media/providers` Feature keys instead). The grid
    takes one channel and one day per call: check whether a multi-channel or time-range form exists (`/sections/…/all`
    with `beginsAt<=…` media queries appears in `watchnow` keys). Check how `Media.gridKey` relates to
    `Channel.gridKey`.
13. **Playing Live TV.** Find out which value goes in `{channel}` for `/tune`, and how to turn the tune result into a
    playable URL. The `sessionId` and `consumerId` for `/livetv/sessions/{sessionId}/{consumerId}/index.m3u8` aren't
    sourced. The alternative is the universal transcoder with the tuned item's `key`. Neither source covers this.
14. **DVR.** Spec only. Check that `POST /media/subscriptions` can take the template's `parameters` string as is, plus
    `targetLibrarySectionID` and `type`. Check the DELETE id: `MediaSubscription.key` looks like the id, but
    `MediaGrabOperation.id` and `key` are `null` in the example. There is no documented way to cancel a single
    scheduled airing.
15. **DVR list key name.** Check whether `/livetv/dvrs` returns `Dvr` (as in the spec's example) or `DVR` (as in its
    schema) in JSON.
16. **Transient token.** Plexopedia uses `GET /security/token` with no parameters. The spec uses
    `POST /security/token?type=delegation&scope=all`. It's only needed if we keep the main token out of player URLs.
